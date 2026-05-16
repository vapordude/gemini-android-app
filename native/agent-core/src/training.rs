//! Training-data capture. When the runtime drives a cloud agent and a
//! local agent in parallel, the cloud agent's higher-quality reasoning
//! becomes a teaching signal for the local model. This module is the
//! seam where those tuples get captured — entirely on-device, in a form
//! the user owns and can inspect.
//!
//! Captured tuple:
//!   prompt → { cloud_response, local_response, accepted, latency_ms }
//!
//! Use:
//! - Distillation: fine-tune the local model on cloud responses the user
//!   accepted.
//! - Preference data: pairs (cloud, local) labeled by which the user
//!   chose form an RLHF/DPO corpus.
//! - Eval: keep a hold-out set to measure local-vs-cloud drift over time.
//!
//! Privacy stance: writes go to `filesDir/training/<session>.jsonl`,
//! never leave the device. Same `audit_no_pii` invariant as telemetry
//! does NOT apply here — by definition the captured tuples contain the
//! user's prompts and the model's completions. The user is the data
//! controller. The runtime is the data processor. The export path is
//! explicit (SAF picker), opt-in, and visible in the trace viewer.

use std::fs::OpenOptions;
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AcceptedSide {
    Cloud,
    Local,
    Neither,
    Both,
}

#[derive(Debug, Clone)]
pub struct CapturedTuple {
    pub session: String,
    pub prompt: String,
    pub cloud_response: Option<String>,
    pub local_response: Option<String>,
    pub accepted: AcceptedSide,
    pub cloud_latency_ms: Option<u64>,
    pub local_latency_ms: Option<u64>,
}

pub trait TrainingCapture: Send + Sync {
    fn record(&self, t: CapturedTuple);
}

/// No-op capture — the default for production builds where the user
/// hasn't explicitly enabled training capture.
pub struct NoopCapture;

impl TrainingCapture for NoopCapture {
    fn record(&self, _t: CapturedTuple) {}
}

/// File-backed capture writing one JSON-lines tuple per call to
/// `filesDir/training/<session>.jsonl`. Append-only.
pub struct FileCapture {
    dir: PathBuf,
    lock: Mutex<()>,
}

impl FileCapture {
    pub fn new(dir: PathBuf) -> std::io::Result<Self> {
        std::fs::create_dir_all(&dir)?;
        Ok(Self {
            dir,
            lock: Mutex::new(()),
        })
    }
}

impl TrainingCapture for FileCapture {
    fn record(&self, t: CapturedTuple) {
        let _g = self.lock.lock().unwrap();
        let ts = now_ms();
        let path = self.dir.join(format!("{}.jsonl", sanitize(&t.session)));
        let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&path) else {
            return;
        };
        let _ = writeln!(f, "{}", format_tuple(&t, ts));
    }
}

fn format_tuple(t: &CapturedTuple, ts_ms: u128) -> String {
    let accepted = match t.accepted {
        AcceptedSide::Cloud => "cloud",
        AcceptedSide::Local => "local",
        AcceptedSide::Neither => "neither",
        AcceptedSide::Both => "both",
    };
    let cloud = t
        .cloud_response
        .as_deref()
        .map(escape)
        .map(|s| format!("\"{s}\""))
        .unwrap_or_else(|| "null".into());
    let local = t
        .local_response
        .as_deref()
        .map(escape)
        .map(|s| format!("\"{s}\""))
        .unwrap_or_else(|| "null".into());
    let cloud_ms = t
        .cloud_latency_ms
        .map(|v| v.to_string())
        .unwrap_or_else(|| "null".into());
    let local_ms = t
        .local_latency_ms
        .map(|v| v.to_string())
        .unwrap_or_else(|| "null".into());
    format!(
        r#"{{"ts":{ts_ms},"session":"{}","prompt":"{}","cloud":{cloud},"local":{local},"accepted":"{accepted}","cloud_ms":{cloud_ms},"local_ms":{local_ms}}}"#,
        escape(&t.session),
        escape(&t.prompt),
    )
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

fn sanitize(s: &str) -> String {
    s.chars()
        .map(|c| {
            if c.is_alphanumeric() || c == '-' || c == '_' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn noop_is_silent() {
        let c = NoopCapture;
        c.record(CapturedTuple {
            session: "s".into(),
            prompt: "p".into(),
            cloud_response: Some("c".into()),
            local_response: Some("l".into()),
            accepted: AcceptedSide::Cloud,
            cloud_latency_ms: Some(100),
            local_latency_ms: Some(20),
        });
    }

    #[test]
    fn file_capture_writes_jsonl() {
        let dir = std::env::temp_dir().join(format!("kaimahi-train-{}", std::process::id()));
        let c = FileCapture::new(dir.clone()).unwrap();
        c.record(CapturedTuple {
            session: "demo".into(),
            prompt: "hello".into(),
            cloud_response: Some("hi from cloud".into()),
            local_response: Some("hi from local".into()),
            accepted: AcceptedSide::Cloud,
            cloud_latency_ms: Some(120),
            local_latency_ms: Some(35),
        });
        let path = dir.join("demo.jsonl");
        let contents = std::fs::read_to_string(&path).unwrap();
        assert!(contents.contains(r#""prompt":"hello""#));
        assert!(contents.contains(r#""accepted":"cloud""#));
        assert!(contents.contains(r#""cloud_ms":120"#));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn escapes_quotes_and_newlines() {
        let line = format_tuple(
            &CapturedTuple {
                session: "x".into(),
                prompt: "with \"quote\"\nand newline".into(),
                cloud_response: None,
                local_response: None,
                accepted: AcceptedSide::Neither,
                cloud_latency_ms: None,
                local_latency_ms: None,
            },
            0,
        );
        assert!(line.contains(r#"\"quote\""#));
        assert!(line.contains(r"\n"));
        assert!(line.contains(r#""cloud":null"#));
    }
}
