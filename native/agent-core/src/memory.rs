//! Persistent agent memory. The agent calls `MemoryStore::fold` whenever
//! it emits a `[FOLD_THOUGHT]` marker, and `MemoryStore::recall` when it
//! needs prior context. Implementations:
//!
//! - `InMemoryStore` — RAM-only, scoped to the running session. Default.
//! - `FileMemoryStore` — JSON-lines under `filesDir/memory/<session>.jsonl`.
//!   Hand-rolled, no serde dep; one entry per line, append-only.
//!
//! All entries are typed enum fields — never the raw transcript — so
//! memory stays auditable and doesn't drag tokens-of-doom forward.

use std::fs::{File, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::sync::Mutex;

#[derive(Debug, Clone)]
pub struct Note {
    pub ts_ms: u128,
    pub session: String,
    pub kind: NoteKind,
    pub text: String,
}

#[derive(Debug, Clone)]
pub enum NoteKind {
    Fold,
    ToolResult { name: String, ok: bool },
    Error { kind: &'static str },
}

pub trait MemoryStore: Send {
    fn fold(&mut self, note: Note);
    fn recall(&self, session: &str, max: usize) -> Vec<Note>;
}

pub struct InMemoryStore {
    notes: Vec<Note>,
}

impl InMemoryStore {
    pub fn new() -> Self {
        Self { notes: Vec::new() }
    }
}

impl Default for InMemoryStore {
    fn default() -> Self {
        Self::new()
    }
}

impl MemoryStore for InMemoryStore {
    fn fold(&mut self, note: Note) {
        self.notes.push(note);
    }
    fn recall(&self, session: &str, max: usize) -> Vec<Note> {
        self.notes
            .iter()
            .rev()
            .filter(|n| n.session == session)
            .take(max)
            .cloned()
            .collect()
    }
}

pub struct FileMemoryStore {
    dir: PathBuf,
    handles: Mutex<std::collections::HashMap<String, File>>,
}

impl FileMemoryStore {
    pub fn new(dir: PathBuf) -> std::io::Result<Self> {
        std::fs::create_dir_all(&dir)?;
        Ok(Self {
            dir,
            handles: Mutex::new(Default::default()),
        })
    }
}

impl MemoryStore for FileMemoryStore {
    fn fold(&mut self, note: Note) {
        let line = format_note(&note);
        let mut handles = self.handles.lock().unwrap();
        let path = self.dir.join(format!("{}.jsonl", sanitize(&note.session)));
        let f = handles.entry(note.session.clone()).or_insert_with(|| {
            OpenOptions::new()
                .create(true)
                .append(true)
                .open(&path)
                .unwrap_or_else(|_| File::create("/dev/null").unwrap())
        });
        let _ = writeln!(f, "{line}");
    }
    fn recall(&self, session: &str, max: usize) -> Vec<Note> {
        let path = self.dir.join(format!("{}.jsonl", sanitize(session)));
        let Ok(file) = File::open(&path) else {
            return Vec::new();
        };
        let reader = BufReader::new(file);
        let mut out = Vec::new();
        for line in reader.lines().map_while(Result::ok) {
            if let Some(n) = parse_note(&line, session) {
                out.push(n);
            }
        }
        if out.len() > max {
            let start = out.len() - max;
            out = out[start..].to_vec();
        }
        out
    }
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

fn format_note(n: &Note) -> String {
    let kind = match &n.kind {
        NoteKind::Fold => "fold".to_string(),
        NoteKind::ToolResult { name, ok } => format!("tool:{name}:{ok}"),
        NoteKind::Error { kind } => format!("err:{kind}"),
    };
    let text = n
        .text
        .replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('\n', "\\n");
    format!(
        r#"{{"ts":{},"kind":"{}","text":"{}"}}"#,
        n.ts_ms, kind, text
    )
}

fn parse_note(line: &str, session: &str) -> Option<Note> {
    // Hand-rolled tolerant parser; we only consume what we wrote.
    let ts = extract_num(line, r#""ts":"#)?;
    let kind_raw = extract_str(line, r#""kind":""#)?;
    let text = extract_str(line, r#""text":""#).unwrap_or_default();
    let kind = if kind_raw == "fold" {
        NoteKind::Fold
    } else if let Some(rest) = kind_raw.strip_prefix("tool:") {
        let mut parts = rest.rsplitn(2, ':');
        let ok = parts.next()? == "true";
        let name = parts.next()?.to_string();
        NoteKind::ToolResult { name, ok }
    } else if let Some(rest) = kind_raw.strip_prefix("err:") {
        NoteKind::Error {
            kind: Box::leak(rest.to_string().into_boxed_str()),
        }
    } else {
        return None;
    };
    Some(Note {
        ts_ms: ts,
        session: session.to_string(),
        kind,
        text: text
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\"),
    })
}

fn extract_num(s: &str, key: &str) -> Option<u128> {
    let i = s.find(key)? + key.len();
    let rest = &s[i..];
    let end = rest
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(rest.len());
    rest[..end].parse().ok()
}

fn extract_str(s: &str, key: &str) -> Option<String> {
    let i = s.find(key)? + key.len();
    let rest = &s[i..];
    let mut out = String::new();
    let mut iter = rest.chars();
    while let Some(c) = iter.next() {
        if c == '\\' {
            if let Some(esc) = iter.next() {
                out.push('\\');
                out.push(esc);
            }
        } else if c == '"' {
            return Some(out);
        } else {
            out.push(c);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn in_memory_roundtrip() {
        let mut s = InMemoryStore::new();
        s.fold(Note {
            ts_ms: 1,
            session: "x".into(),
            kind: NoteKind::Fold,
            text: "hello".into(),
        });
        assert_eq!(s.recall("x", 10).len(), 1);
        assert_eq!(s.recall("y", 10).len(), 0);
    }

    #[test]
    fn file_store_roundtrip() {
        let dir = std::env::temp_dir().join(format!("kaimahi-memtest-{}", std::process::id()));
        let mut s = FileMemoryStore::new(dir.clone()).unwrap();
        s.fold(Note {
            ts_ms: 42,
            session: "abc".into(),
            kind: NoteKind::ToolResult {
                name: "read_file".into(),
                ok: true,
            },
            text: "ok".into(),
        });
        s.fold(Note {
            ts_ms: 43,
            session: "abc".into(),
            kind: NoteKind::Error { kind: "network" },
            text: "timeout".into(),
        });
        // Drop the in-memory handle map by recreating the store.
        let s2 = FileMemoryStore::new(dir.clone()).unwrap();
        let recalled = s2.recall("abc", 10);
        assert_eq!(recalled.len(), 2);
        assert!(matches!(recalled[1].kind, NoteKind::Error { .. }));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
