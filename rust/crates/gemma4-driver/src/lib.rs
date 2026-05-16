//! Session orchestration. Owns the loaded model + KV cache + tokenizer
//! and exposes a single `step()` method that the JNI bridge calls per
//! user message:
//!
//! ```text
//!   prompt: String
//!     -> tokenize
//!     -> prefill loop  (one decoder_block_f32 pass per prompt token,
//!                        appending to the KV cache as we go)
//!     -> decode loop   (sample → embed → decoder pass → emit,
//!                        until EOS or max_new_tokens)
//! ```
//!
//! Streaming: each newly-decoded token piece is passed to a caller-
//! supplied `on_token` callback so the JNI side can push incremental
//! updates over the Kotlin callback flow.

#![forbid(unsafe_op_in_unsafe_fn)]

use gemma4_model::{decoder_block_f32, Gemma4Config, GlobalWeights, KvCache, LayerWeights};
use gemma4_ops::rmsnorm::rmsnorm_f32;
use gemma4_ops::rope::rope_freqs_f32;
use gemma4_ops::sampler::{sample_token, SamplerCfg, Xoshiro};
use gemma4_tokenizer::Tokenizer;

/// One inference session — model + tokenizer + KV cache + RNG. Weights
/// live behind `Option<Weights>` so `step()` can return a typed
/// "weights not loaded" status instead of panicking when called on a
/// freshly-constructed session.
pub struct Session {
    pub config: Gemma4Config,
    pub tokenizer: Tokenizer,
    pub kv: KvCache,
    pub seed: u64,
    weights: Option<Weights>,
    /// Cached RoPE freqs at `(max_position, head_dim, rope_theta)`.
    /// Built lazily on first forward pass.
    rope: Option<(Vec<f32>, Vec<f32>)>,
    /// Position the next token will land at inside the KV cache.
    pub pos: usize,
}

pub struct Weights {
    pub global: GlobalWeights,
    pub layers: Vec<LayerWeights>,
}

impl Session {
    /// Metadata-only session. `step()` returns `WeightsMissing`.
    pub fn new(config: Gemma4Config, tokenizer: Tokenizer, seed: u64) -> Self {
        let kv = KvCache::new(&config);
        Session {
            config,
            tokenizer,
            kv,
            seed,
            weights: None,
            rope: None,
            pos: 0,
        }
    }

    /// Session bound to real weights. Forwards through every decoder
    /// block on every `step()` call.
    pub fn with_weights(
        config: Gemma4Config,
        tokenizer: Tokenizer,
        weights: Weights,
        seed: u64,
    ) -> Self {
        let kv = KvCache::new(&config);
        Session {
            config,
            tokenizer,
            kv,
            seed,
            weights: Some(weights),
            rope: None,
            pos: 0,
        }
    }

    /// Reset the per-conversation state. Weights, tokenizer, and the
    /// cached RoPE table survive.
    pub fn reset(&mut self) {
        self.kv = KvCache::new(&self.config);
        self.pos = 0;
    }

    /// Forward one token through every decoder block; the residual
    /// stream lands in `x_out`. The token enters at position `pos` in
    /// the KV cache.
    fn forward_one(
        &mut self,
        token_id: u32,
        pos: usize,
        x_out: &mut [f32],
    ) -> Result<(), StepStatus> {
        let w = self.weights.as_ref().ok_or(StepStatus::WeightsMissing)?;
        let cfg = &self.config;
        let hidden = cfg.hidden_size;

        if self.rope.is_none() {
            self.rope = Some(rope_freqs_f32(cfg.max_position, cfg.head_dim, cfg.rope_theta));
        }
        let (cos, sin) = self.rope.as_ref().unwrap();

        // Embedding lookup + the canonical Gemma scale-by-sqrt(hidden)
        // applied before the first norm.
        let idx = (token_id as usize).min(cfg.vocab_size.saturating_sub(1));
        let row = &w.global.embed_tokens[idx * hidden..(idx + 1) * hidden];
        x_out.copy_from_slice(row);
        let scale = (hidden as f32).sqrt();
        for v in x_out.iter_mut() {
            *v *= scale;
        }

        for layer_idx in 0..cfg.num_layers {
            let layer = &w.layers[layer_idx];
            decoder_block_f32(
                x_out, cfg, layer_idx, pos, layer, &mut self.kv, cos, sin, token_id,
            );
        }
        Ok(())
    }

    /// Final-layer RMSNorm → lm_head matvec (or tied embedding when
    /// `lm_head` is absent). Writes into `logits` (length `vocab_size`).
    fn compute_logits(&self, x: &[f32], logits: &mut [f32]) -> Result<(), StepStatus> {
        let w = self.weights.as_ref().ok_or(StepStatus::WeightsMissing)?;
        let cfg = &self.config;
        let hidden = cfg.hidden_size;
        let mut norm = vec![0.0_f32; hidden];
        rmsnorm_f32(x, &w.global.norm_final, cfg.rms_norm_eps, &mut norm);
        // The head is laid out `[vocab, hidden]` row-major (embed_tokens
        // shape). One dot product per vocab row.
        let head: &[f32] = w.global.lm_head.as_ref().unwrap_or(&w.global.embed_tokens);
        for v in 0..cfg.vocab_size {
            let row = &head[v * hidden..(v + 1) * hidden];
            let mut acc = 0.0_f32;
            for i in 0..hidden {
                acc += norm[i] * row[i];
            }
            logits[v] = acc;
        }
        Ok(())
    }

    /// Run prefill + decode for `prompt`, emitting each decoded piece
    /// to `on_token`. Stops at the tokenizer's EOS or after
    /// `max_new_tokens`, whichever comes first.
    pub fn step(
        &mut self,
        prompt: &str,
        max_new_tokens: usize,
        sampler_cfg: SamplerCfg,
        on_token: &mut dyn FnMut(&str),
    ) -> StepStatus {
        let ids = self.tokenizer.encode(prompt);
        if ids.is_empty() {
            return StepStatus::EmptyPrompt;
        }
        if self.weights.is_none() {
            return StepStatus::WeightsMissing;
        }
        let cfg_max = self.config.max_position;
        let cfg_vocab = self.config.vocab_size;
        let cfg_hidden = self.config.hidden_size;

        let mut rng = Xoshiro::from_seed(self.seed.wrapping_add(sampler_cfg.seed));
        let mut x = vec![0.0_f32; cfg_hidden];
        let mut logits = vec![0.0_f32; cfg_vocab];

        // Prefill — every prompt token gets a forward pass that fills
        // its slot in the KV cache. We compute logits only on the last
        // prompt token; that's the next-token distribution decode
        // starts from.
        let mut last_logits_valid = false;
        for (i, &id) in ids.iter().enumerate() {
            if self.pos >= cfg_max {
                return StepStatus::HitMaxTokens;
            }
            if let Err(s) = self.forward_one(id, self.pos, &mut x) {
                return s;
            }
            if i + 1 == ids.len() {
                if let Err(s) = self.compute_logits(&x, &mut logits) {
                    return s;
                }
                last_logits_valid = true;
            }
            self.pos += 1;
            self.kv.set_seq_len(self.pos);
        }

        // Decode loop: sample → emit → forward → repeat.
        let mut decoded = 0usize;
        while decoded < max_new_tokens {
            if !last_logits_valid {
                return StepStatus::WeightsMissing;
            }
            let next = sample_token(&mut logits, &sampler_cfg, &mut rng);
            if next == self.tokenizer.eos {
                return StepStatus::Done;
            }
            // Emit the piece before advancing position so an external
            // collector counting tokens stays in sync with `decoded`.
            let piece = self.tokenizer.decode(&[next]);
            if !piece.is_empty() {
                on_token(&piece);
            }
            decoded += 1;
            if decoded >= max_new_tokens || self.pos >= cfg_max {
                return StepStatus::HitMaxTokens;
            }
            if let Err(s) = self.forward_one(next, self.pos, &mut x) {
                return s;
            }
            if let Err(s) = self.compute_logits(&x, &mut logits) {
                return s;
            }
            self.pos += 1;
            self.kv.set_seq_len(self.pos);
        }
        StepStatus::HitMaxTokens
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum StepStatus {
    /// EOS hit; the model decided it was done.
    Done,
    HitMaxTokens,
    EmptyPrompt,
    /// Weights aren't bound. JNI returns this as "fall back to remote".
    WeightsMissing,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_without_weights_returns_status() {
        let cfg = Gemma4Config::e2b_placeholder();
        let mut sess = Session::new(cfg, Tokenizer::placeholder(), 42);
        sess.reset();
        let mut got: Vec<String> = Vec::new();
        let status = sess.step(
            "hi",
            8,
            SamplerCfg {
                temperature: 0.0,
                top_k: 0,
                top_p: 1.0,
                seed: 0,
            },
            &mut |t| got.push(t.to_string()),
        );
        assert_eq!(status, StepStatus::WeightsMissing);
    }

    /// Synthesise a tiny fully-bound session: 2 layers, 8-d hidden,
    /// non-zero embeddings. We're only checking that prefill + decode
    /// run without panicking and terminate cleanly. Quality of the
    /// emitted tokens is meaningless at this size — it's the wiring
    /// we're proving.
    #[test]
    fn tiny_session_runs_prefill_and_decode() {
        let mut cfg = Gemma4Config::e2b_placeholder();
        cfg.num_layers = 2;
        cfg.kv_shared_layers = 0;
        cfg.hidden_size = 8;
        cfg.intermediate_size = 16;
        cfg.num_query_heads = 4;
        cfg.num_kv_heads = 2;
        cfg.head_dim = 2;
        cfg.max_position = 16;
        cfg.ple_dim = 4;
        cfg.vocab_size = 512;
        cfg.tied_embeddings = true;

        let hidden = cfg.hidden_size;
        let inter = cfg.intermediate_size;
        let q_total = cfg.num_query_heads * cfg.head_dim;
        let kv_total = cfg.num_kv_heads * cfg.head_dim;

        let layer = LayerWeights {
            norm_pre_attn: vec![1.0; hidden],
            norm_post_attn: vec![1.0; hidden],
            norm_pre_ffn: vec![1.0; hidden],
            norm_post_ffn: vec![1.0; hidden],
            w_q: vec![0.01; hidden * q_total],
            w_k: vec![0.01; hidden * kv_total],
            w_v: vec![0.01; hidden * kv_total],
            w_o: vec![0.01; q_total * hidden],
            w_gate: vec![0.01; hidden * inter],
            w_up: vec![0.01; hidden * inter],
            w_down: vec![0.01; inter * hidden],
            w_repair: vec![0.0; cfg.ple_dim * hidden],
            ple_table: vec![0.0; cfg.vocab_size * cfg.num_layers * cfg.ple_dim],
        };
        let weights = Weights {
            global: GlobalWeights {
                embed_tokens: (0..cfg.vocab_size * hidden)
                    .map(|i| ((i % 17) as f32 - 8.0) * 0.01)
                    .collect(),
                norm_final: vec![1.0; hidden],
                lm_head: None,
            },
            layers: vec![layer.clone(), layer],
        };

        let mut sess = Session::with_weights(cfg, Tokenizer::placeholder(), weights, 7);
        let mut tokens = Vec::new();
        let status = sess.step(
            "hi",
            4,
            SamplerCfg {
                temperature: 0.0,
                top_k: 0,
                top_p: 1.0,
                seed: 0,
            },
            &mut |t| tokens.push(t.to_string()),
        );
        assert!(matches!(
            status,
            StepStatus::HitMaxTokens | StepStatus::Done
        ));
    }
}
