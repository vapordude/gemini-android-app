//! Session orchestration. Owns the loaded model + KV cache + tokenizer and
//! exposes a single `step()` method that the JNI bridge calls per user
//! prompt:
//!
//! ```text
//!   prompt: String
//!     -> tokenize
//!     -> prefill loop  (one decoder pass per prompt token, no KV reuse yet)
//!     -> decode loop   (sample → embed → decoder pass → emit, until EOS / max)
//! ```
//!
//! Streaming: each newly-decoded token piece is passed to a caller-supplied
//! callback so the JNI side can push incremental updates over the Kotlin
//! callback flow.

#![forbid(unsafe_op_in_unsafe_fn)]

use gemma4_model::{Gemma4Config, KvCache};
use gemma4_ops::sampler::{SamplerCfg, Xoshiro};
use gemma4_tokenizer::Tokenizer;

pub struct Session {
    pub config: Gemma4Config,
    pub tokenizer: Tokenizer,
    pub kv: KvCache,
    pub seed: u64,
}

impl Session {
    pub fn new(config: Gemma4Config, tokenizer: Tokenizer, seed: u64) -> Self {
        let kv = KvCache::new(&config);
        Session { config, tokenizer, kv, seed }
    }

    /// Reset the per-conversation state. Weights and tokenizer survive.
    pub fn reset(&mut self) {
        self.kv = KvCache::new(&self.config);
    }

    /// Run prefill + decode for `prompt`, emitting each decoded piece to
    /// `on_token`. Stops at EOS or after `max_new_tokens`.
    ///
    /// This is the **shape** of the loop. The actual prefill + per-token
    /// forward calls depend on `decoder_block_f32` + the loaded weights,
    /// which are wired in once `gemma4_model::weights` is hooked to mmap
    /// (the next chunk of work). Until then, calls return early with the
    /// "not yet implemented" status.
    pub fn step(
        &mut self,
        prompt: &str,
        max_new_tokens: usize,
        sampler_cfg: SamplerCfg,
        on_token: &mut dyn FnMut(&str),
    ) -> StepStatus {
        let ids = self.tokenizer.encode(prompt);
        if ids.is_empty() { return StepStatus::EmptyPrompt; }

        let mut rng = Xoshiro::from_seed(self.seed.wrapping_add(sampler_cfg.seed));
        let _ = &mut rng; // kept for when sampling comes online

        // Prefill: one forward pass per prompt token. Weights aren't loaded
        // in this milestone, so we skip the actual decode_block_f32 invocation
        // and just account positions in the KV cache.
        let prefill_len = ids.len();
        self.kv.set_seq_len(prefill_len);

        // Decode loop. With placeholder weights we have no logits, so we
        // return WeightsMissing immediately. The wire format here is
        // exercised by the parity tests in gemma4-ops.
        let _ = max_new_tokens;
        let _ = &on_token;

        StepStatus::WeightsMissing
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum StepStatus {
    Done,
    HitMaxTokens,
    EmptyPrompt,
    /// Weights aren't bound yet. The pipeline is wired and reachable but
    /// can't produce logits. JNI returns this as "fall back to remote".
    WeightsMissing,
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Session can be constructed and reset without panic. End-to-end
    /// decoding waits on the weight-loader milestone.
    #[test]
    fn session_roundtrips_reset() {
        let cfg = Gemma4Config::e2b_placeholder();
        let mut sess = Session::new(cfg, Tokenizer::placeholder(), 42);
        sess.reset();
        let mut got: Vec<String> = Vec::new();
        let status = sess.step(
            "hi",
            8,
            SamplerCfg { temperature: 0.0, top_k: 0, top_p: 1.0, seed: 0 },
            &mut |t| got.push(t.to_string()),
        );
        assert_eq!(status, StepStatus::WeightsMissing);
    }
}
