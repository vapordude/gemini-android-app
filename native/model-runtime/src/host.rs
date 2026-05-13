//! Host layer above the model's hot loop: tokenization, sampling,
//! streaming. This is where probes D5/D6 live — never inside `forward`.

use crate::{KvCache, Model, TokenId};

pub fn tokenize(_text: &str) -> Vec<TokenId> {
    // TODO: SentencePiece decode/encode. v0 returns empty.
    Vec::new()
}

pub fn detokenize(_tokens: &[TokenId]) -> String {
    // TODO.
    String::new()
}

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

pub struct StreamConfig {
    pub max_new_tokens: usize,
    pub temperature: f32,
    pub top_p: f32,
    pub stop: Vec<String>,
}

pub fn stream<F: FnMut(TokenId, &str) -> bool>(
    model: &mut dyn Model,
    kv: &mut KvCache,
    prompt: &str,
    cfg: &StreamConfig,
    mut on_token: F,
) {
    let prompt_tokens = tokenize(prompt);
    for &t in &prompt_tokens {
        let _ = model.forward(t, kv);
    }
    for _ in 0..cfg.max_new_tokens {
        // For v0 there are no real logits; bail.
        let last_token = *prompt_tokens.last().unwrap_or(&0);
        let logits = model.forward(last_token, kv);
        let next = sample_greedy(logits);
        let piece = detokenize(&[next]);
        if !on_token(next, &piece) {
            break;
        }
    }
}
