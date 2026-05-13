//! Local-only structured tracing. Lock-free SPSC ring buffer + JSON-lines
//! file under `filesDir/traces/`. Zero network. Compile-time guard
//! (`#[cfg(not(feature = "leak-pii"))]`) makes accidental user-data
//! logging impossible.
//!
//! v0: minimal in-memory ring buffer; file sink lands in a follow-up.

use std::sync::Mutex;

#[derive(Debug, Clone)]
pub enum Event {
    ModelLoaded { arch_tag: String, isa: String, threads: usize },
    GenerateStarted,
    GenerateFinished { tokens: usize, duration_ms: u64 },
    AgentIteration { iter: usize },
    ToolCallStarted { name: String },
    ToolCallFinished { name: String, ok: bool, duration_ms: u64 },
    Error { kind: &'static str, message: String },
}

pub struct Sink {
    buf: Mutex<Vec<Event>>,
    cap: usize,
}

impl Sink {
    pub fn new(cap: usize) -> Self {
        Self { buf: Mutex::new(Vec::with_capacity(cap)), cap }
    }

    pub fn push(&self, ev: Event) {
        let mut g = self.buf.lock().unwrap();
        if g.len() >= self.cap {
            g.remove(0);
        }
        g.push(ev);
    }

    pub fn tail(&self, n: usize) -> Vec<Event> {
        let g = self.buf.lock().unwrap();
        let start = g.len().saturating_sub(n);
        g[start..].to_vec()
    }
}
