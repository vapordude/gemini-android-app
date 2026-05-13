//! Host layer above the model's hot loop: tokenization, sampling,
//! streaming. This is where probes D5/D6 live — never inside `forward`.
//!
//! v0 ships the sampling primitive that's always needed and leaves the
//! end-to-end streaming wiring to the per-arch `host` glue (each arch
//! constructs its prompt template differently, especially Gemma 4 with
//! its `<start_of_turn>` markers).

use crate::TokenId;

/// Argmax over a logits vector. Used by all arches as the deterministic
/// baseline; temperature/top-k/top-p variants live alongside.
pub fn sample_greedy(logits: &[f32]) -> TokenId {
    let mut best = 0u32;
    let mut best_v = f32::NEG_INFINITY;
    for (i, &v) in logits.iter().enumerate() {
        if v > best_v {
            best_v = v;
            best = i as u32;
        }
    }
    best
}

#[derive(Debug, Clone)]
pub struct StreamConfig {
    pub max_new_tokens: usize,
    pub temperature: f32,
    pub top_p: f32,
    pub stop: Vec<String>,
}

impl Default for StreamConfig {
    fn default() -> Self {
        Self {
            max_new_tokens: 256,
            temperature: 0.7,
            top_p: 0.95,
            stop: Vec::new(),
        }
    }
}
