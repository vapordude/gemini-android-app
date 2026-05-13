//! Topological, time-aware agent memory.
//!
//! Notes are nodes in a per-session DAG. Each note has:
//!   - a sortable id (`NoteId` — millis-encoded, sequence-tiebroken)
//!   - typed kind + free-form text written by the agent itself
//!   - typed outbound links to other notes ("follows", "supersedes",
//!     "contradicts", ...) — this is where topology lives
//!   - timestamps for *creation* and an optional *valid_until* — this
//!     is where time-awareness lives
//!   - tags for cheap retrieval
//!
//! Recall is the interesting half. Five orthogonal queries:
//!   - `recall(session, max)` — chronological, newest first (back-compat).
//!   - `recall_window(session, since_ms, max)` — time-windowed.
//!   - `recall_topology(session, root, depth, kinds)` — DAG traversal.
//!   - `recall_by_tag(session, tag, max)` — index lookup.
//!   - `recall_decayed(session, now_ms, half_life_ms, max)` — recency-
//!     weighted, biased toward fresh notes without dropping old ones.
//!
//! Privacy: every byte stays in `filesDir/memory/<session>.jsonl` on
//! device. Tags + text are user data — never sent anywhere by this
//! module. See `PRIVACY.md`.

use std::collections::HashSet;
use std::fs::{File, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::sync::Mutex;

/// 128-bit sortable id: top 80 bits = ms-since-epoch, bottom 48 bits =
/// monotonic counter for tiebreaks at the same millisecond. Lexicographic
/// order = creation order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct NoteId(pub u128);

impl NoteId {
    pub fn new(ts_ms: u128, seq: u64) -> Self {
        Self(((ts_ms & ((1 << 80) - 1)) << 48) | (seq as u128 & ((1u128 << 48) - 1)))
    }
    pub fn ts_ms(&self) -> u128 {
        self.0 >> 48
    }
    pub fn to_hex(&self) -> String {
        format!("{:032x}", self.0)
    }
    pub fn from_hex(s: &str) -> Option<Self> {
        u128::from_str_radix(s, 16).ok().map(Self)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum NoteKind {
    Fold,
    ToolResult { name: String, ok: bool },
    Error { kind: &'static str },
    Fact,
    Observation,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LinkKind {
    Follows,     // happened-after
    CausedBy,    // this note exists because of the target
    Contradicts, // this note disagrees with the target
    Supersedes,  // this note replaces the target
    Refines,     // this note adds nuance to the target
    References,  // generic association
}

impl LinkKind {
    pub fn tag(self) -> &'static str {
        match self {
            Self::Follows => "follows",
            Self::CausedBy => "caused_by",
            Self::Contradicts => "contradicts",
            Self::Supersedes => "supersedes",
            Self::Refines => "refines",
            Self::References => "references",
        }
    }
    pub fn from_tag(t: &str) -> Option<Self> {
        Some(match t {
            "follows" => Self::Follows,
            "caused_by" => Self::CausedBy,
            "contradicts" => Self::Contradicts,
            "supersedes" => Self::Supersedes,
            "refines" => Self::Refines,
            "references" => Self::References,
            _ => return None,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Link {
    pub target: NoteId,
    pub kind: LinkKind,
}

#[derive(Debug, Clone)]
pub struct Note {
    pub id: NoteId,
    pub ts_ms: u128,
    pub session: String,
    pub kind: NoteKind,
    pub text: String,
    pub links: Vec<Link>,
    /// Wall-clock millis after which this note should be treated as
    /// stale. None = no expiry. Useful for "Bob is on call until Friday"
    /// or "the deploy is in flight" type notes.
    pub valid_until_ms: Option<u128>,
    pub tags: Vec<String>,
}

impl Note {
    pub fn fold(id: NoteId, session: impl Into<String>, text: impl Into<String>) -> Self {
        Self {
            id,
            ts_ms: id.ts_ms(),
            session: session.into(),
            kind: NoteKind::Fold,
            text: text.into(),
            links: Vec::new(),
            valid_until_ms: None,
            tags: Vec::new(),
        }
    }
    pub fn with_link(mut self, target: NoteId, kind: LinkKind) -> Self {
        self.links.push(Link { target, kind });
        self
    }
    pub fn with_tag(mut self, tag: impl Into<String>) -> Self {
        self.tags.push(tag.into());
        self
    }
    pub fn expires_at(mut self, ts_ms: u128) -> Self {
        self.valid_until_ms = Some(ts_ms);
        self
    }
    /// Live = exists + not yet past `valid_until_ms` at the given clock.
    pub fn is_live(&self, now_ms: u128) -> bool {
        self.valid_until_ms.map(|v| now_ms < v).unwrap_or(true)
    }
}

pub trait MemoryStore: Send {
    fn fold(&mut self, note: Note);
    fn recall(&self, session: &str, max: usize) -> Vec<Note>;
    fn recall_window(&self, session: &str, since_ms: u128, max: usize) -> Vec<Note> {
        self.recall(session, usize::MAX)
            .into_iter()
            .filter(|n| n.ts_ms >= since_ms)
            .take(max)
            .collect()
    }
    fn recall_by_tag(&self, session: &str, tag: &str, max: usize) -> Vec<Note> {
        self.recall(session, usize::MAX)
            .into_iter()
            .filter(|n| n.tags.iter().any(|t| t == tag))
            .take(max)
            .collect()
    }
    fn recall_topology(
        &self,
        session: &str,
        root: NoteId,
        depth: usize,
        kinds: &[LinkKind],
    ) -> Vec<Note> {
        let all = self.recall(session, usize::MAX);
        let mut by_id: std::collections::HashMap<NoteId, &Note> =
            all.iter().map(|n| (n.id, n)).collect();
        let mut seen: HashSet<NoteId> = HashSet::new();
        let mut frontier = vec![root];
        let mut out = Vec::new();
        for _ in 0..=depth {
            let mut next: Vec<NoteId> = Vec::new();
            for id in frontier.drain(..) {
                if !seen.insert(id) {
                    continue;
                }
                let Some(n) = by_id.remove(&id) else {
                    continue;
                };
                out.push(n.clone());
                for link in &n.links {
                    if kinds.is_empty() || kinds.contains(&link.kind) {
                        next.push(link.target);
                    }
                }
            }
            frontier = next;
            if frontier.is_empty() {
                break;
            }
        }
        out
    }
    /// Recency-weighted recall. Returns notes ordered by score
    /// `exp(-elapsed / half_life)`. Older notes still appear; they just
    /// rank lower.
    fn recall_decayed(
        &self,
        session: &str,
        now_ms: u128,
        half_life_ms: u128,
        max: usize,
    ) -> Vec<Note> {
        if half_life_ms == 0 {
            return self.recall(session, max);
        }
        let half_life = half_life_ms as f64;
        let mut scored: Vec<(f64, Note)> = self
            .recall(session, usize::MAX)
            .into_iter()
            .filter(|n| n.is_live(now_ms))
            .map(|n| {
                let elapsed = now_ms.saturating_sub(n.ts_ms) as f64;
                let score = (-(elapsed * f64::ln(2.0) / half_life)).exp();
                (score, n)
            })
            .collect();
        scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
        scored.into_iter().map(|(_, n)| n).take(max).collect()
    }
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
        out.reverse();
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
        NoteKind::Fact => "fact".to_string(),
        NoteKind::Observation => "obs".to_string(),
    };
    let mut links_s = String::from("[");
    for (i, l) in n.links.iter().enumerate() {
        if i > 0 {
            links_s.push(',');
        }
        links_s.push_str(&format!(
            r#"{{"id":"{}","kind":"{}"}}"#,
            l.target.to_hex(),
            l.kind.tag()
        ));
    }
    links_s.push(']');

    let mut tags_s = String::from("[");
    for (i, t) in n.tags.iter().enumerate() {
        if i > 0 {
            tags_s.push(',');
        }
        tags_s.push('"');
        tags_s.push_str(&escape(t));
        tags_s.push('"');
    }
    tags_s.push(']');

    let valid = n
        .valid_until_ms
        .map(|v| v.to_string())
        .unwrap_or_else(|| "null".into());

    format!(
        r#"{{"id":"{}","ts":{},"kind":"{}","text":"{}","links":{},"tags":{},"valid_until":{}}}"#,
        n.id.to_hex(),
        n.ts_ms,
        kind,
        escape(&n.text),
        links_s,
        tags_s,
        valid,
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

fn parse_note(line: &str, session: &str) -> Option<Note> {
    let id = extract_str(line, r#""id":""#)?;
    let id = NoteId::from_hex(&id)?;
    let ts = extract_num(line, r#""ts":"#)?;
    let kind_raw = extract_str(line, r#""kind":""#)?;
    let text = unescape(&extract_str(line, r#""text":""#).unwrap_or_default());
    let kind = parse_kind(&kind_raw)?;
    let links = parse_links(line);
    let tags = parse_string_array(line, r#""tags":["#);
    let valid_until_ms = extract_optional_num(line, r#""valid_until":"#);
    Some(Note {
        id,
        ts_ms: ts,
        session: session.to_string(),
        kind,
        text,
        links,
        valid_until_ms,
        tags,
    })
}

fn parse_kind(s: &str) -> Option<NoteKind> {
    if s == "fold" {
        Some(NoteKind::Fold)
    } else if s == "fact" {
        Some(NoteKind::Fact)
    } else if s == "obs" {
        Some(NoteKind::Observation)
    } else if let Some(rest) = s.strip_prefix("tool:") {
        let mut parts = rest.rsplitn(2, ':');
        let ok = parts.next()? == "true";
        let name = parts.next()?.to_string();
        Some(NoteKind::ToolResult { name, ok })
    } else {
        s.strip_prefix("err:").map(|rest| NoteKind::Error {
            kind: Box::leak(rest.to_string().into_boxed_str()),
        })
    }
}

fn parse_links(line: &str) -> Vec<Link> {
    let key = r#""links":["#;
    let Some(start) = line.find(key) else {
        return Vec::new();
    };
    let body_start = start + key.len();
    let Some(rel) = line[body_start..].find(']') else {
        return Vec::new();
    };
    let body = &line[body_start..body_start + rel];
    let mut out = Vec::new();
    let mut rest = body;
    while let Some(obj_start) = rest.find('{') {
        let Some(obj_end) = rest[obj_start..].find('}') else {
            break;
        };
        let obj = &rest[obj_start..obj_start + obj_end + 1];
        let id = extract_str(obj, r#""id":""#).and_then(|s| NoteId::from_hex(&s));
        let kind = extract_str(obj, r#""kind":""#).and_then(|s| LinkKind::from_tag(&s));
        if let (Some(target), Some(kind)) = (id, kind) {
            out.push(Link { target, kind });
        }
        rest = &rest[obj_start + obj_end + 1..];
    }
    out
}

fn parse_string_array(line: &str, key: &str) -> Vec<String> {
    let Some(start) = line.find(key) else {
        return Vec::new();
    };
    let body_start = start + key.len();
    let Some(rel) = line[body_start..].find(']') else {
        return Vec::new();
    };
    let body = &line[body_start..body_start + rel];
    let mut out = Vec::new();
    let mut rest = body;
    while let Some(open) = rest.find('"') {
        let after = &rest[open + 1..];
        let mut acc = String::new();
        let mut chars = after.chars();
        while let Some(c) = chars.next() {
            if c == '\\' {
                if let Some(esc) = chars.next() {
                    acc.push('\\');
                    acc.push(esc);
                }
            } else if c == '"' {
                out.push(unescape(&acc));
                let consumed = open + 1 + acc.chars().map(|c| c.len_utf8()).sum::<usize>() + 1;
                rest = if consumed < rest.len() {
                    &rest[consumed..]
                } else {
                    ""
                };
                break;
            } else {
                acc.push(c);
            }
        }
        if rest.is_empty() {
            break;
        }
    }
    out
}

fn unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '\\' {
            match chars.next() {
                Some('n') => out.push('\n'),
                Some('r') => out.push('\r'),
                Some('t') => out.push('\t'),
                Some('"') => out.push('"'),
                Some('\\') => out.push('\\'),
                Some(other) => {
                    out.push('\\');
                    out.push(other);
                }
                None => {}
            }
        } else {
            out.push(c);
        }
    }
    out
}

fn extract_num(s: &str, key: &str) -> Option<u128> {
    let i = s.find(key)? + key.len();
    let rest = &s[i..];
    let end = rest
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(rest.len());
    rest[..end].parse().ok()
}

fn extract_optional_num(s: &str, key: &str) -> Option<u128> {
    let i = s.find(key)? + key.len();
    let rest = &s[i..];
    if rest.starts_with("null") {
        return None;
    }
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

    fn id(ts: u128, seq: u64) -> NoteId {
        NoteId::new(ts, seq)
    }

    #[test]
    fn note_id_is_time_sortable() {
        let a = id(100, 0);
        let b = id(101, 0);
        let c = id(101, 1);
        assert!(a < b);
        assert!(b < c);
        assert_eq!(a.ts_ms(), 100);
        assert_eq!(b.ts_ms(), 101);
    }

    #[test]
    fn note_id_hex_roundtrip() {
        let i = id(123456, 7);
        let s = i.to_hex();
        assert_eq!(NoteId::from_hex(&s), Some(i));
    }

    #[test]
    fn recall_window_filters_by_time() {
        let mut s = InMemoryStore::new();
        s.fold(Note::fold(id(100, 0), "x", "old"));
        s.fold(Note::fold(id(500, 0), "x", "newer"));
        s.fold(Note::fold(id(800, 0), "x", "newest"));
        let r = s.recall_window("x", 400, 10);
        assert_eq!(r.len(), 2);
        assert!(r.iter().all(|n| n.ts_ms >= 400));
    }

    #[test]
    fn recall_by_tag_indexes() {
        let mut s = InMemoryStore::new();
        s.fold(Note::fold(id(100, 0), "x", "alpha").with_tag("deploy"));
        s.fold(Note::fold(id(200, 0), "x", "beta").with_tag("config"));
        s.fold(Note::fold(id(300, 0), "x", "gamma").with_tag("deploy"));
        let r = s.recall_by_tag("x", "deploy", 10);
        assert_eq!(r.len(), 2);
    }

    #[test]
    fn recall_topology_walks_dag() {
        let mut s = InMemoryStore::new();
        let root = id(100, 0);
        let child_a = id(200, 0);
        let child_b = id(300, 0);
        s.fold(Note::fold(root, "x", "root"));
        s.fold(Note::fold(child_a, "x", "a").with_link(root, LinkKind::CausedBy));
        s.fold(Note::fold(child_b, "x", "b").with_link(child_a, LinkKind::Follows));
        // Topology from child_b, depth 2, any kind.
        let r = s.recall_topology("x", child_b, 2, &[]);
        let texts: Vec<&str> = r.iter().map(|n| n.text.as_str()).collect();
        assert!(texts.contains(&"b"));
        assert!(texts.contains(&"a"));
        assert!(texts.contains(&"root"));
    }

    #[test]
    fn recall_topology_filters_by_link_kind() {
        let mut s = InMemoryStore::new();
        let root = id(100, 0);
        let related = id(200, 0);
        s.fold(Note::fold(root, "x", "root"));
        s.fold(Note::fold(related, "x", "related").with_link(root, LinkKind::References));
        let only_caused = s.recall_topology("x", related, 5, &[LinkKind::CausedBy]);
        // Only `related` itself; the References link wasn't followed.
        assert_eq!(only_caused.len(), 1);
    }

    #[test]
    fn recall_decayed_prefers_recent() {
        let mut s = InMemoryStore::new();
        s.fold(Note::fold(id(100, 0), "x", "old"));
        s.fold(Note::fold(id(900, 0), "x", "new"));
        let r = s.recall_decayed("x", 1000, 500, 10);
        assert_eq!(r[0].text, "new");
        assert_eq!(r[1].text, "old");
    }

    #[test]
    fn recall_decayed_skips_expired() {
        let mut s = InMemoryStore::new();
        s.fold(Note::fold(id(100, 0), "x", "expired").expires_at(500));
        s.fold(Note::fold(id(200, 0), "x", "live"));
        let r = s.recall_decayed("x", 1000, 500, 10);
        assert!(r.iter().all(|n| n.text != "expired"));
    }

    #[test]
    fn file_store_persists_links_tags_validity() {
        let dir = std::env::temp_dir().join(format!("kaimahi-topo-{}", std::process::id()));
        let mut s = FileMemoryStore::new(dir.clone()).unwrap();
        let root = id(100, 0);
        let child = id(200, 0);
        s.fold(
            Note::fold(root, "abc", "root")
                .with_tag("dep")
                .expires_at(9999),
        );
        s.fold(
            Note::fold(child, "abc", "child")
                .with_link(root, LinkKind::CausedBy)
                .with_tag("dep"),
        );
        let s2 = FileMemoryStore::new(dir.clone()).unwrap();
        let r = s2.recall("abc", 10);
        assert_eq!(r.len(), 2);
        let child_note = r.iter().find(|n| n.text == "child").unwrap();
        assert_eq!(child_note.links.len(), 1);
        assert_eq!(child_note.links[0].kind, LinkKind::CausedBy);
        assert_eq!(child_note.links[0].target, root);
        assert!(child_note.tags.iter().any(|t| t == "dep"));
        let root_note = r.iter().find(|n| n.text == "root").unwrap();
        assert_eq!(root_note.valid_until_ms, Some(9999));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
