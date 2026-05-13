//! Side-channel diagnostic probes. Off by default. When the `diag` feature
//! is on, probe call sites push typed events to a background channel.
//! When off, the macro expands to nothing so the hot loop stays branch-free.
//!
//! Insertion points D1..D11 are documented in the plan.

#[cfg(feature = "diag")]
pub mod sink {
    use std::sync::OnceLock;
    use std::sync::mpsc::{channel, Sender};

    static TX: OnceLock<Sender<Probe>> = OnceLock::new();

    #[derive(Debug)]
    pub enum Probe {
        ModelLoaded { arch_tag: String, isa: String, threads: usize },
        Layer { idx: usize, l2: f32 },
        Kernel { name: &'static str, ns: u64, isa: &'static str },
        QuantBlock { codec: &'static str, max_err: f32 },
        Tokenizer { roundtrip_ok: bool, len: usize },
        Sampler { token_id: u32, prob: f32 },
        KvWrite { slot: usize, len: usize, evicted: bool },
        AgentTransition { from: &'static str, to: &'static str },
        ToolCall { name: String, ns: u64, ret_class: &'static str },
    }

    pub fn install() {
        let (tx, _rx) = channel();
        let _ = TX.set(tx);
        // TODO: spawn background consumer that writes to traces/diag.jsonl.
    }

    pub fn emit(p: Probe) {
        if let Some(tx) = TX.get() {
            let _ = tx.send(p);
        }
    }
}

#[macro_export]
macro_rules! probe {
    ($($t:tt)*) => {
        #[cfg(feature = "diag")]
        { $crate::sink::emit($($t)*); }
    };
}
