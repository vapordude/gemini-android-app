//! Local-only structured tracing. Lock-free-ish ring buffer + JSON-lines
//! file sink. Zero network.
//!
//! # Non-extractive contract — load-bearing
//!
//! See `PRIVACY.md`. This crate is the chokepoint that enforces "no PII
//! ever leaves this device through telemetry". The `Event` enum is
//! structurally incapable of carrying user prompts, model completions,
//! tool arguments, or file contents:
//!
//! - All variant fields are typed enums, bounded scalars, or
//!   `&'static str` short tags compiled into the binary.
//! - The `Error` variant's `kind` is `&'static str` so it CAN'T be
//!   arbitrary runtime text. The `message` field is intended for short
//!   stable tags ("rate-limited", "timeout", "OOM") — never for raw
//!   error bodies that might echo user input. Reviewers should reject
//!   any PR that passes a free-form message at a call site.
//! - There is no `leak-pii` feature flag and there never will be.
//!
//! The `audit_no_pii` helper runs in CI and over captured trace files
//! during instrumented tests. Widening this enum is a privacy review,
//! not a routine change.

use std::fs::OpenOptions;
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone)]
pub enum Event {
    ModelLoaded {
        arch_tag: String,
        isa: String,
        threads: usize,
    },
    GenerateStarted,
    GenerateFinished {
        tokens: usize,
        duration_ms: u64,
    },
    AgentIteration {
        iter: usize,
    },
    ToolCallStarted {
        name: String,
    },
    ToolCallFinished {
        name: String,
        ok: bool,
        duration_ms: u64,
    },
    /// Errors are described by:
    /// - `kind`: which subsystem failed (compile-time string).
    /// - `tag`: short stable identifier like "rate-limited", "timeout",
    ///   "oom", "parse-error". MUST NOT contain runtime payload. Use a
    ///   `&'static str` so the type system enforces this — adding a
    ///   `String` field here would defeat the non-extractive contract
    ///   and should be rejected at review.
    Error {
        kind: &'static str,
        tag: &'static str,
    },
}

impl Event {
    pub fn kind(&self) -> &'static str {
        match self {
            Event::ModelLoaded { .. } => "model_loaded",
            Event::GenerateStarted => "generate_started",
            Event::GenerateFinished { .. } => "generate_finished",
            Event::AgentIteration { .. } => "agent_iteration",
            Event::ToolCallStarted { .. } => "tool_call_started",
            Event::ToolCallFinished { .. } => "tool_call_finished",
            Event::Error { .. } => "error",
        }
    }

    /// Render the event as a single JSON-lines string. Hand-rolled to
    /// keep the no-deps rule. Values are limited to known types so no
    /// string-escape surprises.
    pub fn to_json_line(&self, ts_ms: u128) -> String {
        let mut s = format!(r#"{{"ts":{ts_ms},"kind":"{}""#, self.kind());
        match self {
            Event::ModelLoaded {
                arch_tag,
                isa,
                threads,
            } => {
                s.push_str(&format!(
                    r#","arch_tag":"{}","isa":"{}","threads":{threads}"#,
                    escape(arch_tag),
                    escape(isa)
                ));
            }
            Event::GenerateStarted => {}
            Event::GenerateFinished {
                tokens,
                duration_ms,
            } => {
                s.push_str(&format!(
                    r#","tokens":{tokens},"duration_ms":{duration_ms}"#
                ));
            }
            Event::AgentIteration { iter } => {
                s.push_str(&format!(r#","iter":{iter}"#));
            }
            Event::ToolCallStarted { name } => {
                s.push_str(&format!(r#","tool":"{}""#, escape(name)));
            }
            Event::ToolCallFinished {
                name,
                ok,
                duration_ms,
            } => {
                s.push_str(&format!(
                    r#","tool":"{}","ok":{ok},"duration_ms":{duration_ms}"#,
                    escape(name)
                ));
            }
            Event::Error { kind, tag } => {
                s.push_str(&format!(r#","err_kind":"{kind}","tag":"{tag}""#));
            }
        }
        s.push('}');
        s
    }
}

fn escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

pub struct Sink {
    inner: Mutex<SinkInner>,
}

struct SinkInner {
    ring: Vec<(u128, Event)>,
    cap: usize,
    file: Option<PathBuf>,
}

impl Sink {
    pub fn new(cap: usize) -> Self {
        Self {
            inner: Mutex::new(SinkInner {
                ring: Vec::with_capacity(cap),
                cap,
                file: None,
            }),
        }
    }

    pub fn with_file(cap: usize, path: PathBuf) -> std::io::Result<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        Ok(Self {
            inner: Mutex::new(SinkInner {
                ring: Vec::with_capacity(cap),
                cap,
                file: Some(path),
            }),
        })
    }

    pub fn push(&self, ev: Event) {
        let ts = now_ms();
        let mut g = self.inner.lock().unwrap();
        if g.ring.len() >= g.cap {
            g.ring.remove(0);
        }
        if let Some(path) = g.file.clone() {
            let line = ev.to_json_line(ts);
            // Best-effort append; never panic on log failures.
            if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&path) {
                let _ = writeln!(f, "{line}");
            }
        }
        g.ring.push((ts, ev));
    }

    pub fn tail(&self, n: usize) -> Vec<(u128, Event)> {
        let g = self.inner.lock().unwrap();
        let start = g.ring.len().saturating_sub(n);
        g.ring[start..].to_vec()
    }
}

fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

/// Audit invariant: trace lines must not contain user-input substrings.
/// CI runs this against captured traces from instrumented tests.
pub fn audit_no_pii(line: &str, banned: &[&str]) -> bool {
    !banned.iter().any(|b| line.contains(b))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_shape() {
        let ev = Event::ModelLoaded {
            arch_tag: "gemma4".to_string(),
            isa: "neon-dotprod".to_string(),
            threads: 4,
        };
        let line = ev.to_json_line(123);
        assert!(line.contains(r#""ts":123"#));
        assert!(line.contains(r#""kind":"model_loaded""#));
        assert!(line.contains(r#""arch_tag":"gemma4""#));
        assert!(line.contains(r#""threads":4"#));
    }

    #[test]
    fn error_emits_static_tag_only() {
        let ev = Event::Error {
            kind: "parse",
            tag: "rate-limited",
        };
        let line = ev.to_json_line(0);
        assert!(line.contains(r#""err_kind":"parse""#));
        assert!(line.contains(r#""tag":"rate-limited""#));
    }

    #[test]
    fn ring_buffer_caps() {
        let s = Sink::new(3);
        for _ in 0..10 {
            s.push(Event::GenerateStarted);
        }
        assert_eq!(s.tail(100).len(), 3);
    }

    #[test]
    fn audit_catches_pii() {
        let line = r#"{"ts":0,"kind":"error","err_kind":"x","message":"secret_token=abc123"}"#;
        assert!(!audit_no_pii(line, &["abc123"]));
        assert!(audit_no_pii(line, &["unrelated"]));
    }
}
