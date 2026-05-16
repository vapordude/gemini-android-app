//! Side-channel diagnostic probes. Off by default. When the `diag`
//! feature is on, probe call sites push typed events to a background
//! channel. When off, `probe!()` expands to nothing and the call site
//! vanishes during optimization.
//!
//! Insertion points (per the plan, see /docs):
//!   D1  model-runtime::load                      arch/isa/threads at load time
//!   D2  arch/<arch>/model.rs after each block    per-layer L2 norms + first-8
//!   D3  tensor-core/kernels/*                    cycle counts + ISA branch
//!   D4  tensor-core/quant/*::dequantize_block    per-block max abs error
//!   D5  model-runtime/host::tokenize             round-trip ok + len
//!   D6  model-runtime/host::sample               top-k slice + chosen
//!   D7  model-runtime/kv::write                  slot + len + evictions
//!   D8  agent-core/lib.rs marker emit sites      state transitions + iter
//!   D9  jni-shim::call_tool                      tool name + latency
//!   D10 agent-bridge AgentRuntime.kt             bridge wall-clock (telemetry)
//!   D11 emdash-core::propose_config              schema-validation outcome

#![deny(unsafe_op_in_unsafe_fn)]

#[derive(Debug, Clone)]
pub enum Probe {
    ModelLoaded {
        arch_tag: String,
        isa: String,
        threads: usize,
    },
    Layer {
        idx: usize,
        l2: f32,
    },
    Kernel {
        name: &'static str,
        ns: u64,
        isa: &'static str,
    },
    QuantBlock {
        codec: &'static str,
        max_err: f32,
    },
    Tokenizer {
        roundtrip_ok: bool,
        len: usize,
    },
    Sampler {
        token_id: u32,
        prob: f32,
    },
    KvWrite {
        slot: usize,
        len: usize,
        evicted: bool,
    },
    AgentTransition {
        from: &'static str,
        to: &'static str,
        iter: usize,
    },
    ToolCall {
        name: String,
        ns: u64,
        ret_class: &'static str,
    },
    ConfigDiff {
        adds: usize,
        updates: usize,
        deletes: usize,
        ok: bool,
    },
    /// Per-layer architecture resolution at load time. Emitted once per
    /// layer when a Gemma 4 model is bound to weights — covers the
    /// reviewer's "diagnostic probes" suggestion (per-layer type +
    /// KV-share decisions + RoPE config).
    Gemma4Layer {
        idx: usize,
        ty: &'static str, // "sliding" or "full"
        head_dim: usize,
        rope_base: f32,
        n_rot: usize,
        window: Option<usize>,
        owns_kv: bool,
        kv_alias: usize,
    },
    /// Per-load summary of Gemma 4 model shape — emitted once after
    /// `Gemma4Model::load` finishes binding tensors. Cheap to read
    /// from a CI log to confirm the model is what we expect.
    Gemma4LoadSummary {
        n_layers: usize,
        hidden_size: usize,
        ple_dim: usize,
        kv_owning_layers: usize,
        kv_reusing_layers: usize,
        final_logit_softcap: Option<f32>,
        tied_embeddings: bool,
    },
}

#[cfg(feature = "diag")]
pub mod sink {
    use super::Probe;
    use std::sync::mpsc::{channel, Sender};
    use std::sync::OnceLock;

    static TX: OnceLock<Sender<Probe>> = OnceLock::new();

    pub fn install() {
        let (tx, rx) = channel();
        if TX.set(tx).is_err() {
            return;
        }
        std::thread::spawn(move || {
            for probe in rx {
                // v0 sink: stderr. A follow-up plugs telemetry::Sink in.
                eprintln!("[diag] {:?}", probe);
            }
        });
    }

    pub fn emit(p: Probe) {
        if let Some(tx) = TX.get() {
            let _ = tx.send(p);
        }
    }
}

#[cfg(not(feature = "diag"))]
pub mod sink {
    use super::Probe;
    pub fn install() {}
    pub fn emit(_p: Probe) {}
}

/// `probe!(expr)` — expression that evaluates to a `Probe`. When the
/// `diag` feature is off, the entire call site disappears.
#[macro_export]
macro_rules! probe {
    ($p:expr) => {
        #[cfg(feature = "diag")]
        {
            $crate::sink::emit($p);
        }
    };
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn probe_macro_compiles_off() {
        // With `diag` feature off (default), this expands to nothing.
        probe!(Probe::ModelLoaded {
            arch_tag: "gemma4".to_string(),
            isa: "scalar".to_string(),
            threads: 1,
        });
    }

    #[test]
    fn install_is_idempotent_when_off() {
        sink::install();
        sink::install();
    }
}
