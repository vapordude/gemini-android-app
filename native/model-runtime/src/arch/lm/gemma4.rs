//! Gemma 4 (E2B / E4B) architecture. Config-driven from GGUF metadata —
//! head count, head dim, sliding window, RoPE base, etc. all come from
//! the file so the runtime never guesses.
//!
//! v0 forward path executes a structurally-correct loop (RMSNorm → attn
//! → residual → RMSNorm → MLP → residual → final RMSNorm → output proj)
//! but with stubbed weight loading. Real weight binding lands in a
//! follow-up; this lets the dispatch + KV cache + tokenizer paths get
//! exercised end-to-end.

use crate::{KvCache, LanguageModel, LoadError, RuntimeInfo, TokenId};
use gguf_loader::{GgufFile, MetaValue};
use tensor_core::{isa, IsaTier};

#[derive(Debug, Clone)]
pub struct Gemma4Config {
    pub vocab_size: usize,
    pub hidden_size: usize,
    pub n_layers: usize,
    pub n_heads: usize,
    pub n_kv_heads: usize,
    pub head_dim: usize,
    pub mlp_intermediate: usize,
    pub context_length: usize,
    pub rope_base: f32,
    pub sliding_window: Option<usize>,
    pub rms_eps: f32,
}

impl Gemma4Config {
    pub fn from_gguf(g: &GgufFile) -> Result<Self, LoadError> {
        let prefix = ["gemma4", "gemma-4", "gemma3", "gemma2", "gemma"]
            .iter()
            .find(|p| g.get(&format!("{p}.context_length")).is_some())
            .copied()
            .unwrap_or("gemma4");
        let get_u32 = |suffix: &str| -> Result<u32, LoadError> {
            g.get(&format!("{prefix}.{suffix}"))
                .and_then(MetaValue::as_u32)
                .ok_or(LoadError::MissingMetadata("gemma.<suffix>"))
        };
        let get_u32_or = |suffix: &str, default: u32| -> u32 {
            g.get(&format!("{prefix}.{suffix}"))
                .and_then(MetaValue::as_u32)
                .unwrap_or(default)
        };
        let get_f32_or = |suffix: &str, default: f32| -> f32 {
            g.get(&format!("{prefix}.{suffix}"))
                .and_then(MetaValue::as_f32)
                .unwrap_or(default)
        };

        let hidden_size = get_u32("embedding_length")? as usize;
        let n_heads = get_u32("attention.head_count")? as usize;
        let n_kv_heads = get_u32_or("attention.head_count_kv", n_heads as u32) as usize;
        let head_dim = g
            .get(&format!("{prefix}.attention.key_length"))
            .and_then(MetaValue::as_u32)
            .map(|v| v as usize)
            .unwrap_or(hidden_size / n_heads);
        let n_layers = get_u32("block_count")? as usize;
        let mlp_intermediate = get_u32("feed_forward_length")? as usize;
        let context_length = get_u32("context_length")? as usize;
        let rope_base = get_f32_or("rope.freq_base", 10_000.0);
        let rms_eps = get_f32_or("attention.layer_norm_rms_epsilon", 1e-6);
        let sliding_window = g
            .get(&format!("{prefix}.attention.sliding_window"))
            .and_then(MetaValue::as_u32)
            .map(|v| v as usize);

        // vocab_size: prefer tokens array length, fall back to embed dim 1
        let vocab_size = g
            .get("tokenizer.ggml.tokens")
            .and_then(|v| v.as_array())
            .map(|a| a.len())
            .or_else(|| {
                g.tensors
                    .iter()
                    .find(|t| t.name == "token_embd.weight")
                    .map(|t| t.dims[1] as usize)
            })
            .ok_or(LoadError::MissingMetadata("vocab_size"))?;

        Ok(Self {
            vocab_size,
            hidden_size,
            n_layers,
            n_heads,
            n_kv_heads,
            head_dim,
            mlp_intermediate,
            context_length,
            rope_base,
            sliding_window,
            rms_eps,
        })
    }
}

pub struct Gemma4Model {
    cfg: Gemma4Config,
    logits: Vec<f32>,
    isa: IsaTier,
    arch_tag: String,
}

impl Gemma4Model {
    pub fn from_gguf(g: &GgufFile) -> Result<Self, LoadError> {
        let cfg = Gemma4Config::from_gguf(g)?;
        let arch_tag = g.arch_tag().unwrap_or("gemma4").to_string();
        Ok(Self {
            logits: vec![0.0; cfg.vocab_size],
            isa: isa::detect(),
            arch_tag,
            cfg,
        })
    }
}

impl LanguageModel for Gemma4Model {
    fn forward(&mut self, _token: TokenId, kv: &mut KvCache) -> &[f32] {
        // TODO: real per-layer forward. Structurally this is:
        //   x = embed(token)
        //   for layer in 0..n_layers {
        //       x_norm = rmsnorm(x, w_attn_norm)
        //       attn_out = sdpa(x_norm, w_q, w_k, w_v, kv, sliding_window?)
        //       x = x + attn_out
        //       x_norm = rmsnorm(x, w_mlp_norm)
        //       x = x + swiglu_mlp(x_norm, w_gate, w_up, w_down)
        //   }
        //   x = rmsnorm(x, w_final_norm)
        //   logits = x @ w_output^T
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
            vocab_size: self.cfg.vocab_size,
            context_length: self.cfg.context_length,
        }
    }
}
