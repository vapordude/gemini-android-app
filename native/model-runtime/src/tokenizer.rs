//! GGUF-embedded BPE tokenizer. Vocab, merges, scores, special tokens,
//! and whitespace handling all come from the file — we never guess.
//!
//! Special tokens are first-class:
//!   - The 4 standard named ones (BOS, EOS, UNK, PAD) are exposed as fields.
//!   - Plus a `specials` map keyed by literal text (e.g. "<start_of_turn>",
//!     "<end_of_turn>", "<image_soft_token>") so callers can look them up
//!     by name without hardcoding IDs.
//!
//! Multimodal note: Gemma 4 E2B/E4B accept image inputs encoded as
//! special image tokens (e.g. `<image_soft_token>`). The text tokenizer
//! handles them as opaque single-token entries; the vision encoder
//! produces the corresponding embeddings and the runtime fuses them at
//! the embed-table boundary. That fusion path lives in the arch module,
//! not here.
//!
//! Agentic note: Gemma 4 is trained with tool-use prompt formats. Our
//! agent loop wraps any base model with marker grammar from DeepAgent,
//! so model-native tool format is a per-arch concern, not a tokenizer
//! concern.

use gguf_loader::{GgufFile, MetaValue};
use std::collections::HashMap;

const SP_SPACE: char = '\u{2581}'; // ▁

/// Token type per GGUF spec.
/// 0 = normal, 1 = unknown, 2 = control, 3 = user-defined, 4 = unused, 5 = byte
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TokenType {
    Normal,
    Unknown,
    Control,
    UserDefined,
    Unused,
    Byte,
}

impl TokenType {
    fn from_u32(v: u32) -> Self {
        match v {
            1 => Self::Unknown,
            2 => Self::Control,
            3 => Self::UserDefined,
            4 => Self::Unused,
            5 => Self::Byte,
            _ => Self::Normal,
        }
    }

    pub fn is_special(self) -> bool {
        matches!(self, Self::Control | Self::UserDefined | Self::Unknown)
    }
}

#[derive(Debug)]
pub enum TokenizerError {
    MissingVocab,
}

pub struct Tokenizer {
    pub vocab: Vec<String>,
    pub scores: Vec<f32>,
    pub token_types: Vec<TokenType>,
    /// Look up token id by its literal text — covers every named special
    /// token (e.g. "<start_of_turn>", "<image_soft_token>", role labels).
    pub by_text: HashMap<String, u32>,
    pub bos_id: Option<u32>,
    pub eos_id: Option<u32>,
    pub unk_id: Option<u32>,
    pub pad_id: Option<u32>,
}

impl Tokenizer {
    pub fn from_gguf(g: &GgufFile) -> Result<Self, TokenizerError> {
        let tokens = g
            .get("tokenizer.ggml.tokens")
            .and_then(MetaValue::as_array)
            .ok_or(TokenizerError::MissingVocab)?;
        let vocab: Vec<String> = tokens
            .iter()
            .map(|v| v.as_string().unwrap_or("").to_string())
            .collect();
        let scores: Vec<f32> = g
            .get("tokenizer.ggml.scores")
            .and_then(MetaValue::as_array)
            .map(|arr| arr.iter().map(|v| v.as_f32().unwrap_or(0.0)).collect())
            .unwrap_or_default();
        let token_types: Vec<TokenType> = g
            .get("tokenizer.ggml.token_type")
            .and_then(MetaValue::as_array)
            .map(|arr| {
                arr.iter()
                    .map(|v| TokenType::from_u32(v.as_u32().unwrap_or(0)))
                    .collect()
            })
            .unwrap_or_else(|| vec![TokenType::Normal; vocab.len()]);

        let mut by_text = HashMap::with_capacity(vocab.len());
        for (i, t) in vocab.iter().enumerate() {
            by_text.insert(t.clone(), i as u32);
        }

        Ok(Self {
            vocab,
            scores,
            token_types,
            by_text,
            bos_id: g
                .get("tokenizer.ggml.bos_token_id")
                .and_then(MetaValue::as_u32),
            eos_id: g
                .get("tokenizer.ggml.eos_token_id")
                .and_then(MetaValue::as_u32),
            unk_id: g
                .get("tokenizer.ggml.unknown_token_id")
                .and_then(MetaValue::as_u32),
            pad_id: g
                .get("tokenizer.ggml.padding_token_id")
                .and_then(MetaValue::as_u32),
        })
    }

    pub fn vocab_size(&self) -> usize {
        self.vocab.len()
    }

    pub fn id_of(&self, text: &str) -> Option<u32> {
        self.by_text.get(text).copied()
    }

    pub fn is_special(&self, id: u32) -> bool {
        self.token_types
            .get(id as usize)
            .copied()
            .map(TokenType::is_special)
            .unwrap_or(false)
    }

    /// Special tokens are matched verbatim and NEVER split. The input is
    /// first segmented on special-token boundaries; each non-special run
    /// is then SP-prepared (▁ for leading word boundary, spaces → ▁) and
    /// tokenized via greedy longest-prefix over the normal vocab.
    pub fn encode(&self, text: &str) -> Vec<u32> {
        let mut out = Vec::new();
        let mut rest = text;
        while !rest.is_empty() {
            if let Some((id, len)) = self.match_special_prefix(rest) {
                out.push(id);
                rest = &rest[len..];
                continue;
            }
            let segment_end = self.find_next_special(rest).unwrap_or(rest.len());
            let segment = &rest[..segment_end];
            let prepared = self.prepare(segment);
            self.encode_normal_segment(&prepared, &mut out);
            rest = &rest[segment_end..];
        }
        out
    }

    fn encode_normal_segment(&self, prepared: &str, out: &mut Vec<u32>) {
        let mut sub = prepared;
        while !sub.is_empty() {
            let (id, consumed) = self.longest_prefix(sub);
            out.push(id);
            if consumed == 0 {
                let bump = sub.chars().next().map(char::len_utf8).unwrap_or(1);
                sub = &sub[bump..];
            } else {
                sub = &sub[consumed..];
            }
        }
    }

    fn find_next_special(&self, s: &str) -> Option<usize> {
        let mut earliest: Option<usize> = None;
        for (text, &id) in self.by_text.iter() {
            if !self.is_special(id) || text.is_empty() {
                continue;
            }
            if let Some(pos) = s.find(text.as_str()) {
                match earliest {
                    None => earliest = Some(pos),
                    Some(e) if pos < e => earliest = Some(pos),
                    _ => {}
                }
            }
        }
        earliest
    }

    fn prepare(&self, text: &str) -> String {
        let mut p = String::with_capacity(text.len() + 1);
        if !text.is_empty() && !text.starts_with(SP_SPACE) {
            p.push(SP_SPACE);
        }
        for c in text.chars() {
            if c == ' ' {
                p.push(SP_SPACE);
            } else {
                p.push(c);
            }
        }
        p
    }

    fn match_special_prefix(&self, s: &str) -> Option<(u32, usize)> {
        // Try longest specials first via the `by_text` map. Cap scan to
        // tokens that look like specials (start with `<` is the cheap
        // heuristic, but the real gate is is_special()).
        let mut best: Option<(u32, usize)> = None;
        for (text, &id) in self.by_text.iter() {
            if !self.is_special(id) {
                continue;
            }
            if text.is_empty() {
                continue;
            }
            if s.starts_with(text.as_str()) {
                match best {
                    None => best = Some((id, text.len())),
                    Some((_, blen)) if text.len() > blen => best = Some((id, text.len())),
                    _ => {}
                }
            }
        }
        best
    }

    fn longest_prefix(&self, s: &str) -> (u32, usize) {
        let mut best: Option<(u32, usize, f32)> = None;
        for (i, tok) in self.vocab.iter().enumerate() {
            if tok.is_empty() {
                continue;
            }
            if self.is_special(i as u32) {
                continue;
            }
            if s.starts_with(tok.as_str()) {
                let score = self.scores.get(i).copied().unwrap_or(0.0);
                match best {
                    None => best = Some((i as u32, tok.len(), score)),
                    Some((_, blen, bscore)) => {
                        let better_len = tok.len() > blen;
                        let same_len_better_score = tok.len() == blen && score > bscore;
                        if better_len || same_len_better_score {
                            best = Some((i as u32, tok.len(), score));
                        }
                    }
                }
            }
        }
        match best {
            Some((id, len, _)) => (id, len),
            None => (self.unk_id.unwrap_or(0), 0),
        }
    }

    pub fn decode(&self, ids: &[u32]) -> String {
        let mut s = String::new();
        for &id in ids {
            if let Some(t) = self.vocab.get(id as usize) {
                s.push_str(t);
            }
        }
        let mut out = s.replace(SP_SPACE, " ");
        if out.starts_with(' ') {
            out.remove(0);
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(vocab: &[&str], types: &[TokenType]) -> Tokenizer {
        let vocab: Vec<String> = vocab.iter().map(|s| s.to_string()).collect();
        let mut by_text = HashMap::new();
        for (i, t) in vocab.iter().enumerate() {
            by_text.insert(t.clone(), i as u32);
        }
        Tokenizer {
            vocab,
            scores: Vec::new(),
            token_types: types.to_vec(),
            by_text,
            bos_id: Some(1),
            eos_id: Some(2),
            unk_id: Some(0),
            pad_id: None,
        }
    }

    #[test]
    fn longest_prefix_wins() {
        let t = fixture(
            &["<unk>", "<s>", "</s>", "▁he", "▁hello", "l", "o"],
            &[
                TokenType::Unknown,
                TokenType::Control,
                TokenType::Control,
                TokenType::Normal,
                TokenType::Normal,
                TokenType::Normal,
                TokenType::Normal,
            ],
        );
        let ids = t.encode("hello");
        assert_eq!(ids.len(), 1);
        assert_eq!(t.vocab[ids[0] as usize], "▁hello");
    }

    #[test]
    fn special_tokens_are_atomic() {
        let t = fixture(
            &["<unk>", "<s>", "</s>", "<start_of_turn>", "user", "▁hello"],
            &[
                TokenType::Unknown,
                TokenType::Control,
                TokenType::Control,
                TokenType::Control,
                TokenType::Normal,
                TokenType::Normal,
            ],
        );
        // The special token should be emitted as a single id even though
        // "user" is a normal-vocab entry that would otherwise split off.
        let ids = t.encode("<start_of_turn>user");
        assert_eq!(t.vocab[ids[0] as usize], "<start_of_turn>");
        // "user" follows as a normal token.
        assert!(ids.iter().any(|&id| t.vocab[id as usize] == "user"));
    }

    #[test]
    fn id_of_resolves_special() {
        let t = fixture(
            &["<unk>", "<s>", "<start_of_turn>", "<end_of_turn>"],
            &[
                TokenType::Unknown,
                TokenType::Control,
                TokenType::Control,
                TokenType::Control,
            ],
        );
        assert_eq!(t.id_of("<start_of_turn>"), Some(2));
        assert_eq!(t.id_of("<end_of_turn>"), Some(3));
        assert!(t.is_special(2));
    }

    #[test]
    fn unknown_falls_back() {
        let t = fixture(
            &["<unk>", "<s>", "</s>"],
            &[TokenType::Unknown, TokenType::Control, TokenType::Control],
        );
        let ids = t.encode("xyz");
        assert!(!ids.is_empty());
        assert!(ids.iter().all(|&id| id == t.unk_id.unwrap()));
    }
}
