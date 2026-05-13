//! Gemma 4 (E2B / E4B) — sliding-window attention, RMSNorm, GeGLU, RoPE.
//!
//! v0 ships a stub `Model` that returns zero logits. The real architecture
//! lands in a follow-up commit once the kernels in `tensor-core` are wired.

use crate::{KvCache, Model, RuntimeInfo, TokenId};
use tensor_core::{isa, IsaTier};

pub struct Gemma4Model {
    logits: Vec<f32>,
    isa: IsaTier,
    arch_tag: String,
    vocab_size: usize,
    context_length: usize,
}

impl Gemma4Model {
    pub fn stub(header: &gguf_loader::GgufHeader) -> Self {
        let vocab_size = 256_000;
        Self {
            logits: vec![0.0; vocab_size],
            isa: isa::detect(),
            arch_tag: header.arch_tag.clone(),
            vocab_size,
            context_length: 8192,
        }
    }
}

impl Model for Gemma4Model {
    fn forward(&mut self, _token: TokenId, kv: &mut KvCache) -> &[f32] {
        kv.seq_len = kv.seq_len.saturating_add(1);
        &self.logits
    }

    fn reset(&mut self, kv: &mut KvCache) {
        kv.seq_len = 0;
    }

    fn info(&self) -> RuntimeInfo {
        RuntimeInfo {
            version: env!("CARGO_PKG_VERSION"),
            arch_tag: self.arch_tag.clone(),
            isa: self.isa,
            threads: 1,
            vocab_size: self.vocab_size,
            context_length: self.context_length,
        }
    }
}
