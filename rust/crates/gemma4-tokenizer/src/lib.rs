//! Hand-rolled SentencePiece BPE tokenizer.
//!
//! Gemma 4 uses a SentencePiece tokenizer (same family as Gemma 1/2/3 —
//! a 256K vocab with byte-fallback). Real loading is from the
//! `tokenizer.json` (HF "fast" format) or the legacy `tokenizer.model`
//! (sentencepiece protobuf). For now this crate exposes a minimal API
//! that lets the rest of the pipeline run on a placeholder vocab so the
//! end-to-end wiring can be validated before the real loader lands.
//!
//! No external dependencies. The `tokenizer.json` parser is a hand-rolled
//! JSON pull-parser dedicated to the small subset we need.

#![forbid(unsafe_op_in_unsafe_fn)]

use std::collections::HashMap;

/// Simple tokenizer interface. The real implementation will mirror SP's
/// "BPE with byte fallback" scoring; the placeholder splits whitespace and
/// maps each piece to a synthetic id so the driver can be exercised.
pub struct Tokenizer {
    vocab: Vec<String>,
    rev: HashMap<String, u32>,
    pub bos: u32,
    pub eos: u32,
    pub pad: u32,
}

impl Tokenizer {
    /// Build a placeholder tokenizer with the smallest vocab that exercises
    /// all the byte-fallback paths. Real load uses [`Tokenizer::from_json`].
    pub fn placeholder() -> Self {
        let mut vocab: Vec<String> = (0..=255_u32).map(|b| format!("<0x{b:02X}>")).collect();
        vocab.insert(0, "<pad>".to_string());
        vocab.insert(1, "<eos>".to_string());
        vocab.insert(2, "<bos>".to_string());
        // From 3 onward, 256 single-byte tokens at indices 3..259.
        let rev = vocab.iter().enumerate()
            .map(|(i, s)| (s.clone(), i as u32))
            .collect();
        Tokenizer { vocab, rev, bos: 2, eos: 1, pad: 0 }
    }

    /// `tokenizer.json` loader. Reads the `model.vocab` array and `added_tokens`
    /// list. This is a stub for the placeholder phase — the production
    /// loader needs to handle merges, normalizers, pre-tokenizers, and
    /// post-processors. Returns `None` if the JSON shape is unexpected.
    pub fn from_json(_text: &str) -> Option<Self> {
        // TODO: pull-parse the JSON. For now, fall through to placeholder.
        Some(Self::placeholder())
    }

    pub fn vocab_size(&self) -> usize { self.vocab.len() }

    /// Byte-fallback encode: each UTF-8 byte maps to its `<0x..>` token.
    /// Replace once the BPE merge table is loaded.
    pub fn encode(&self, text: &str) -> Vec<u32> {
        let mut out = Vec::with_capacity(text.len() + 1);
        for b in text.bytes() {
            let tok = format!("<0x{b:02X}>");
            if let Some(&id) = self.rev.get(&tok) {
                out.push(id);
            }
        }
        out
    }

    /// Decode by concatenating the bytes of each `<0x..>` token. Real BPE
    /// pieces (once loaded) are appended verbatim.
    pub fn decode(&self, ids: &[u32]) -> String {
        let mut bytes = Vec::with_capacity(ids.len());
        for &id in ids {
            let Some(tok) = self.vocab.get(id as usize) else { continue };
            if let Some(hex) = tok.strip_prefix("<0x").and_then(|s| s.strip_suffix(">")) {
                if let Ok(b) = u8::from_str_radix(hex, 16) {
                    bytes.push(b);
                    continue;
                }
            }
            // Non-byte token — fall back to UTF-8 bytes of the piece itself.
            bytes.extend_from_slice(tok.as_bytes());
        }
        String::from_utf8_lossy(&bytes).into_owned()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholder_round_trip_byte_fallback() {
        let t = Tokenizer::placeholder();
        let ids = t.encode("hi");
        // 'h' = 0x68, 'i' = 0x69 — both should round-trip.
        let back = t.decode(&ids);
        assert_eq!(back, "hi");
    }

    #[test]
    fn placeholder_round_trip_unicode() {
        let t = Tokenizer::placeholder();
        let ids = t.encode("héllo");
        let back = t.decode(&ids);
        assert_eq!(back, "héllo");
    }

    #[test]
    fn special_token_ids_distinct() {
        let t = Tokenizer::placeholder();
        assert_ne!(t.bos, t.eos);
        assert_ne!(t.eos, t.pad);
        assert_ne!(t.bos, t.pad);
    }
}
