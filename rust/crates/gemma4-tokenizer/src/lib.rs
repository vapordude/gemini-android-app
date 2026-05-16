//! Hand-rolled SentencePiece-BPE tokenizer for Gemma. No external deps.
//!
//! The `tokenizer.json` Gemma ships under `transformers` v4+ is the
//! HF "fast" format. The fields we read:
//!
//! - `added_tokens`        — list of `{id, content, special}` records.
//! - `model.vocab`         — map `String → u32`.
//! - `model.merges`        — ordered list of `"left right"` strings.
//! - `model.byte_fallback` — whether `<0xNN>` tokens cover OOV bytes.
//! - `model.unk_token`     — fallback id when byte_fallback is off.
//!
//! We do NOT implement `normalizer` / `pre_tokenizer` /
//! `post_processor`. Gemma's `tokenizer.json` ships with
//! `normalizer: null`, an empty `pre_tokenizer`, and a
//! `TemplateProcessing` post-processor that adds `<bos>` — but for
//! inference we want the caller to decide whether to inject BOS, so
//! the implementation here intentionally returns just the BPE result.
//!
//! Encode algorithm:
//!
//!   1. Walk the input, peeling off any added-special-token at the
//!      current cursor. Special tokens are emitted as a single id and
//!      never split.
//!   2. For each non-special segment, prepend SentencePiece's `▁`
//!      (U+2581) at the word boundary and replace spaces with `▁`.
//!   3. Convert each character to a base token id (or byte-fallback to
//!      a sequence of `<0xNN>` ids).
//!   4. Iteratively merge adjacent pairs using the merge table; the
//!      lowest-rank merge wins on every pass.
//!
//! Decode is the reverse: concatenate the token strings, byte-fallback
//! tokens are decoded back to their UTF-8 byte, then `▁` becomes a
//! space and the SP-prefix space is trimmed.

#![forbid(unsafe_op_in_unsafe_fn)]

use std::collections::HashMap;

const SP_SPACE: char = '\u{2581}';

/// Special-token entry from `added_tokens`. We keep `text` because the
/// scanner matches by literal text in [`Tokenizer::encode`].
#[derive(Clone, Debug)]
pub struct Special {
    pub id: u32,
    pub text: String,
}

pub struct Tokenizer {
    /// id → token text. Index is the id.
    pub vocab: Vec<String>,
    /// token text → id. Built from `vocab` at load time.
    pub by_text: HashMap<String, u32>,
    /// `(left_id, right_id) → (rank, merged_id)`. Lower rank wins.
    merges: HashMap<(u32, u32), (u32, u32)>,
    /// Specials, longest-first so the prefix matcher peels off the
    /// longest viable token at the cursor.
    specials: Vec<Special>,
    /// Lookup by SentencePiece `<0xNN>` form into the id of that byte.
    /// `None` when `byte_fallback` is off in the source tokenizer.
    byte_fallback: Option<[Option<u32>; 256]>,
    pub bos: u32,
    pub eos: u32,
    pub pad: u32,
    pub unk: u32,
}

impl Tokenizer {
    /// Build a placeholder tokenizer that round-trips 256 single-byte
    /// inputs. Used by tests + the synthetic-weights path; production
    /// code calls [`Tokenizer::from_json`] instead.
    pub fn placeholder() -> Self {
        let mut vocab: Vec<String> = Vec::with_capacity(259);
        vocab.push("<pad>".to_string());
        vocab.push("<eos>".to_string());
        vocab.push("<bos>".to_string());
        for b in 0..=255u32 {
            vocab.push(format!("<0x{b:02X}>"));
        }
        let by_text = vocab
            .iter()
            .enumerate()
            .map(|(i, s)| (s.clone(), i as u32))
            .collect();
        let mut byte_fallback = [None; 256];
        for (idx, _byte_str) in (0..=255u32).map(|b| (b, format!("<0x{b:02X}>"))) {
            byte_fallback[idx as usize] = Some(3 + idx);
        }
        Tokenizer {
            vocab,
            by_text,
            merges: HashMap::new(),
            specials: vec![
                Special { id: 0, text: "<pad>".into() },
                Special { id: 1, text: "<eos>".into() },
                Special { id: 2, text: "<bos>".into() },
            ],
            byte_fallback: Some(byte_fallback),
            bos: 2,
            eos: 1,
            pad: 0,
            unk: 0,
        }
    }

    /// Parse a real Gemma `tokenizer.json`. Returns `None` if the JSON
    /// structure isn't recognisable (missing `model.vocab`, missing
    /// `model.merges`, etc).
    pub fn from_json(text: &str) -> Option<Self> {
        let vocab_map = parse_vocab(text)?;
        let merges = parse_merges(text)?;
        let added = parse_added_tokens(text);

        // Build id-indexed vocab from the (text → id) map.
        let max_id = vocab_map.values().copied().max().unwrap_or(0);
        let mut vocab = vec![String::new(); (max_id + 1) as usize];
        for (s, id) in &vocab_map {
            let idx = *id as usize;
            if idx < vocab.len() {
                vocab[idx] = s.clone();
            }
        }
        // Added tokens may declare ids past the BPE vocab end; grow.
        for (text, id) in added.iter().map(|s| (&s.text, s.id)) {
            let idx = id as usize;
            if idx >= vocab.len() {
                vocab.resize(idx + 1, String::new());
            }
            vocab[idx] = text.clone();
        }
        let mut by_text = HashMap::with_capacity(vocab.len());
        for (i, s) in vocab.iter().enumerate() {
            if !s.is_empty() {
                by_text.insert(s.clone(), i as u32);
            }
        }

        // Build the merge lookup: rank → (left_id, right_id) → merged_id.
        let mut merge_map: HashMap<(u32, u32), (u32, u32)> = HashMap::with_capacity(merges.len());
        for (rank, (left, right)) in merges.iter().enumerate() {
            let left_id = by_text.get(left)?;
            let right_id = by_text.get(right)?;
            let merged = format!("{left}{right}");
            let merged_id = by_text.get(&merged)?;
            merge_map.insert((*left_id, *right_id), (rank as u32, *merged_id));
        }

        // Byte-fallback table: <0x00>..<0xFF>. Optional but Gemma sets
        // byte_fallback=true so we always populate.
        let mut byte_fallback = [None; 256];
        for b in 0..=255u32 {
            let key = format!("<0x{b:02X}>");
            if let Some(&id) = by_text.get(&key) {
                byte_fallback[b as usize] = Some(id);
            }
        }

        // Sort specials longest-first so prefix match peels off the
        // longest match (`<start_of_turn>` beats `<` for instance).
        let mut specials = added;
        specials.sort_by(|a, b| b.text.len().cmp(&a.text.len()));

        // Resolve named control tokens. Fall back to placeholder ids
        // (typical Gemma layout) so callers can still construct a
        // working tokenizer when one of these is missing.
        let bos = by_text.get("<bos>").copied().unwrap_or(2);
        let eos = by_text.get("<eos>").copied().unwrap_or(1);
        let pad = by_text.get("<pad>").copied().unwrap_or(0);
        let unk = by_text.get("<unk>").copied().unwrap_or(0);

        Some(Tokenizer {
            vocab,
            by_text,
            merges: merge_map,
            specials,
            byte_fallback: Some(byte_fallback),
            bos,
            eos,
            pad,
            unk,
        })
    }

    pub fn vocab_size(&self) -> usize {
        self.vocab.len()
    }

    pub fn id_of(&self, text: &str) -> Option<u32> {
        self.by_text.get(text).copied()
    }

    /// Encode a Rust string into a sequence of token ids. Walks the
    /// input peeling specials first, then runs BPE on each segment.
    pub fn encode(&self, text: &str) -> Vec<u32> {
        let mut out = Vec::with_capacity(text.len() / 2);
        let mut rest = text;
        while !rest.is_empty() {
            // Step 1: try to peel a special token at the cursor.
            if let Some((id, len)) = self.match_special_prefix(rest) {
                out.push(id);
                rest = &rest[len..];
                continue;
            }
            // Step 2: walk to the next special-token boundary (or end).
            let next_special = self.find_next_special(rest).unwrap_or(rest.len());
            let segment = &rest[..next_special];
            self.encode_segment(segment, &mut out);
            rest = &rest[next_special..];
        }
        out
    }

    fn encode_segment(&self, segment: &str, out: &mut Vec<u32>) {
        if segment.is_empty() {
            return;
        }
        let prepared = self.prepare(segment);
        // BPE per "word" — SentencePiece splits on the ▁ boundary.
        let mut word_start = 0;
        let chars: Vec<(usize, char)> = prepared.char_indices().collect();
        for i in 0..chars.len() {
            let (byte_idx, ch) = chars[i];
            if ch == SP_SPACE && byte_idx > word_start {
                let word = &prepared[word_start..byte_idx];
                self.bpe_encode(word, out);
                word_start = byte_idx;
            }
        }
        // Tail.
        if word_start < prepared.len() {
            self.bpe_encode(&prepared[word_start..], out);
        }
    }

    /// BPE encode a single "word" (one ▁-prefixed unit). Falls through
    /// to byte-fallback for any character not in the vocab.
    fn bpe_encode(&self, word: &str, out: &mut Vec<u32>) {
        // Step 1: convert each character to its base token id, or to
        // a byte-fallback run.
        let mut ids: Vec<u32> = Vec::with_capacity(word.len());
        for ch in word.chars() {
            let one_char: String = ch.to_string();
            if let Some(&id) = self.by_text.get(&one_char) {
                ids.push(id);
            } else if let Some(table) = self.byte_fallback {
                let mut buf = [0u8; 4];
                let bytes = ch.encode_utf8(&mut buf).as_bytes();
                for b in bytes {
                    if let Some(id) = table[*b as usize] {
                        ids.push(id);
                    } else {
                        ids.push(self.unk);
                    }
                }
            } else {
                ids.push(self.unk);
            }
        }

        // Step 2: iteratively merge the lowest-rank adjacent pair.
        loop {
            let mut best: Option<(usize, u32, u32)> = None; // (index, rank, merged_id)
            for i in 0..ids.len().saturating_sub(1) {
                if let Some(&(rank, merged_id)) = self.merges.get(&(ids[i], ids[i + 1])) {
                    if best.is_none() || rank < best.unwrap().1 {
                        best = Some((i, rank, merged_id));
                    }
                }
            }
            let Some((idx, _, merged_id)) = best else {
                break;
            };
            ids[idx] = merged_id;
            ids.remove(idx + 1);
        }
        out.extend_from_slice(&ids);
    }

    fn match_special_prefix(&self, s: &str) -> Option<(u32, usize)> {
        // specials is sorted longest-first → first match wins.
        for sp in &self.specials {
            if !sp.text.is_empty() && s.starts_with(sp.text.as_str()) {
                return Some((sp.id, sp.text.len()));
            }
        }
        None
    }

    fn find_next_special(&self, s: &str) -> Option<usize> {
        let mut earliest: Option<usize> = None;
        for sp in &self.specials {
            if sp.text.is_empty() {
                continue;
            }
            if let Some(pos) = s.find(sp.text.as_str()) {
                if earliest.map(|e| pos < e).unwrap_or(true) {
                    earliest = Some(pos);
                }
            }
        }
        earliest
    }

    /// SentencePiece prep: replace ' ' with `▁`, prepend `▁` so the
    /// first word starts at a boundary. If the segment already begins
    /// with `▁` (e.g. the caller stitched two segments) we leave it.
    fn prepare(&self, text: &str) -> String {
        let mut p = String::with_capacity(text.len() + 3);
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

    /// Decode by concatenating each id's token text, folding the
    /// `<0xNN>` byte-fallback tokens back to bytes, then reversing the
    /// `▁` substitution.
    pub fn decode(&self, ids: &[u32]) -> String {
        // First pass — collect bytes. Multi-byte UTF-8 characters that
        // were byte-fallbacked at encode time recombine here.
        let mut bytes: Vec<u8> = Vec::with_capacity(ids.len() * 2);
        for &id in ids {
            let Some(piece) = self.vocab.get(id as usize) else {
                continue;
            };
            if let Some(byte) = byte_token_value(piece) {
                bytes.push(byte);
            } else {
                bytes.extend_from_slice(piece.as_bytes());
            }
        }
        let mut s = String::from_utf8_lossy(&bytes).into_owned();
        // Replace ▁ with space and trim the leading marker (added by
        // the `prepare` step at encode time).
        s = s.replace(SP_SPACE, " ");
        if s.starts_with(' ') {
            s.remove(0);
        }
        s
    }
}

/// `<0xNN>` -> the byte value NN. Returns `None` for non-byte tokens.
fn byte_token_value(piece: &str) -> Option<u8> {
    let body = piece.strip_prefix("<0x")?.strip_suffix(">")?;
    u8::from_str_radix(body, 16).ok()
}

// ---- JSON parsing for tokenizer.json ----
// We hand-roll a tiny pull-parser focussed on the fields we need.
// Strings are JSON-escape aware; nested objects/arrays we don't care
// about are skipped with brace-counting.

/// Find `"model": { "vocab": { ... } }` and return the `String -> u32`
/// map. The vocab block in Gemma is huge (256K entries × ~30 bytes),
/// so we walk it once into a HashMap and never re-scan.
fn parse_vocab(text: &str) -> Option<HashMap<String, u32>> {
    let model_off = find_key_object(text, "model")?;
    let inner = &text[model_off..];
    let vocab_off = find_key_object(inner, "vocab")?;
    parse_object_as_string_u32_map(&inner[vocab_off..])
}

/// Parse `"merges": [ "<left> <right>", ... ]` into `Vec<(String, String)>`.
fn parse_merges(text: &str) -> Option<Vec<(String, String)>> {
    let model_off = find_key_object(text, "model")?;
    let inner = &text[model_off..];
    // Find the merges array within model.
    let arr_start = find_key_array(inner, "merges")?;
    let mut i = arr_start;
    let bytes = inner.as_bytes();
    let mut out = Vec::new();
    while i < bytes.len() {
        match bytes[i] {
            b' ' | b'\n' | b'\r' | b'\t' | b',' => i += 1,
            b']' => break,
            b'"' => {
                let (s, next) = parse_json_string(bytes, i).ok()?;
                i = next;
                // Two forms: `"left right"` (old) or `["left","right"]`
                // (newer HF tokenizers, but Gemma uses the old form).
                let split = s.find(' ')?;
                let left = s[..split].to_string();
                let right = s[split + 1..].to_string();
                out.push((left, right));
            }
            b'[' => {
                // Nested-array merge form: ["left", "right"].
                i += 1;
                let mut pair = Vec::with_capacity(2);
                while i < bytes.len() {
                    match bytes[i] {
                        b' ' | b'\n' | b'\r' | b'\t' | b',' => i += 1,
                        b']' => {
                            i += 1;
                            break;
                        }
                        b'"' => {
                            let (s, next) = parse_json_string(bytes, i).ok()?;
                            i = next;
                            pair.push(s);
                        }
                        _ => return None,
                    }
                }
                if pair.len() == 2 {
                    out.push((pair.remove(0), pair.remove(0)));
                }
            }
            _ => return None,
        }
    }
    Some(out)
}

/// Parse `"added_tokens": [ {id, content, special, ...}, ... ]`.
/// Returns Specials sorted by occurrence; the caller sorts longest-first.
fn parse_added_tokens(text: &str) -> Vec<Special> {
    let mut out = Vec::new();
    let Some(arr_start) = find_key_array(text, "added_tokens") else {
        return out;
    };
    let bytes = text.as_bytes();
    let mut i = arr_start;
    while i < bytes.len() {
        match bytes[i] {
            b' ' | b'\n' | b'\r' | b'\t' | b',' => i += 1,
            b']' => break,
            b'{' => {
                let Ok(obj_end) = find_object_end(bytes, i) else {
                    return out;
                };
                let obj = &text[i..=obj_end];
                let id = field_u32(obj, "id");
                let content = field_string(obj, "content");
                let special = field_bool(obj, "special").unwrap_or(false);
                if let (Some(id), Some(content)) = (id, content) {
                    if special || content.starts_with('<') {
                        out.push(Special { id, text: content });
                    }
                }
                i = obj_end + 1;
            }
            _ => return out,
        }
    }
    out
}

fn parse_object_as_string_u32_map(text: &str) -> Option<HashMap<String, u32>> {
    let bytes = text.as_bytes();
    let mut i = 0;
    while i < bytes.len() && bytes[i].is_ascii_whitespace() {
        i += 1;
    }
    if i >= bytes.len() || bytes[i] != b'{' {
        return None;
    }
    i += 1;
    let mut out = HashMap::new();
    while i < bytes.len() {
        match bytes[i] {
            b' ' | b'\n' | b'\r' | b'\t' | b',' => i += 1,
            b'}' => break,
            b'"' => {
                let (k, next) = parse_json_string(bytes, i).ok()?;
                i = next;
                i = skip_ws(bytes, i);
                if i >= bytes.len() || bytes[i] != b':' {
                    return None;
                }
                i += 1;
                i = skip_ws(bytes, i);
                let (v, next) = parse_u32(bytes, i).ok()?;
                i = next;
                out.insert(k, v);
            }
            _ => return None,
        }
    }
    Some(out)
}

/// `find_key_object("foo", text)` returns the offset of the `{` of
/// `"foo": { ... }`, advanced into the object body (just past `{`).
fn find_key_object(text: &str, key: &str) -> Option<usize> {
    let needle = format!("\"{key}\"");
    let i = text.find(&needle)?;
    let after = &text[i + needle.len()..];
    let after = after.trim_start();
    let after = after.strip_prefix(':')?.trim_start();
    let abs = i + needle.len() + (text[i + needle.len()..].len() - after.len());
    let bytes = text.as_bytes();
    if abs >= bytes.len() || bytes[abs] != b'{' {
        return None;
    }
    Some(abs)
}

/// Like `find_key_object` but for arrays. Returns the offset just past `[`.
fn find_key_array(text: &str, key: &str) -> Option<usize> {
    let needle = format!("\"{key}\"");
    let mut search_from = 0;
    while let Some(rel) = text[search_from..].find(&needle) {
        let i = search_from + rel;
        let after = &text[i + needle.len()..];
        let trimmed = after.trim_start();
        if let Some(rest) = trimmed.strip_prefix(':') {
            let rest = rest.trim_start();
            if rest.starts_with('[') {
                let abs = i + needle.len() + (after.len() - rest.len()) + 1;
                return Some(abs);
            }
        }
        search_from = i + needle.len();
    }
    None
}

fn field_u32(obj: &str, key: &str) -> Option<u32> {
    let needle = format!("\"{key}\"");
    let i = obj.find(&needle)?;
    let after = &obj[i + needle.len()..];
    let after = after.trim_start().strip_prefix(':')?.trim_start();
    let bytes = after.as_bytes();
    let (v, _) = parse_u32(bytes, 0).ok()?;
    Some(v)
}

fn field_string(obj: &str, key: &str) -> Option<String> {
    let needle = format!("\"{key}\"");
    let i = obj.find(&needle)?;
    let after = &obj[i + needle.len()..];
    let after = after.trim_start().strip_prefix(':')?.trim_start();
    let bytes = after.as_bytes();
    let (s, _) = parse_json_string(bytes, 0).ok()?;
    Some(s)
}

fn field_bool(obj: &str, key: &str) -> Option<bool> {
    let needle = format!("\"{key}\"");
    let i = obj.find(&needle)?;
    let after = &obj[i + needle.len()..];
    let after = after.trim_start().strip_prefix(':')?.trim_start();
    if after.starts_with("true") {
        Some(true)
    } else if after.starts_with("false") {
        Some(false)
    } else {
        None
    }
}

fn parse_u32(bytes: &[u8], start: usize) -> Result<(u32, usize), &'static str> {
    let mut i = start;
    while i < bytes.len() && bytes[i].is_ascii_whitespace() {
        i += 1;
    }
    let begin = i;
    while i < bytes.len() && bytes[i].is_ascii_digit() {
        i += 1;
    }
    if begin == i {
        return Err("expected digits");
    }
    let s = core::str::from_utf8(&bytes[begin..i]).map_err(|_| "non-ASCII number")?;
    let v: u32 = s.parse().map_err(|_| "u32 parse")?;
    Ok((v, i))
}

fn skip_ws(b: &[u8], mut i: usize) -> usize {
    while i < b.len() && b[i].is_ascii_whitespace() {
        i += 1;
    }
    i
}

/// Parse a JSON string starting at `bytes[start] == b'"'`. Returns the
/// decoded string and the offset just past the closing quote. Handles
/// `\\"`, `\\\\`, `\\n`, `\\t`, `\\r`, `\\u####`, `\\/`.
fn parse_json_string(b: &[u8], start: usize) -> Result<(String, usize), &'static str> {
    if start >= b.len() || b[start] != b'"' {
        return Err("expected '\"'");
    }
    let mut out = String::new();
    let mut i = start + 1;
    while i < b.len() {
        match b[i] {
            b'"' => return Ok((out, i + 1)),
            b'\\' => {
                if i + 1 >= b.len() {
                    return Err("dangling backslash");
                }
                match b[i + 1] {
                    b'"' => {
                        out.push('"');
                        i += 2;
                    }
                    b'\\' => {
                        out.push('\\');
                        i += 2;
                    }
                    b'/' => {
                        out.push('/');
                        i += 2;
                    }
                    b'n' => {
                        out.push('\n');
                        i += 2;
                    }
                    b't' => {
                        out.push('\t');
                        i += 2;
                    }
                    b'r' => {
                        out.push('\r');
                        i += 2;
                    }
                    b'b' => {
                        out.push('\u{08}');
                        i += 2;
                    }
                    b'f' => {
                        out.push('\u{0C}');
                        i += 2;
                    }
                    b'u' => {
                        if i + 5 >= b.len() {
                            return Err("short \\u escape");
                        }
                        let hex = core::str::from_utf8(&b[i + 2..i + 6])
                            .map_err(|_| "non-ASCII \\u escape")?;
                        let cp = u32::from_str_radix(hex, 16).map_err(|_| "bad \\u")?;
                        // Surrogate-pair check: high surrogate must be
                        // followed by `\uDCxx` low surrogate.
                        let scalar = if (0xD800..=0xDBFF).contains(&cp) {
                            if b.get(i + 6) != Some(&b'\\') || b.get(i + 7) != Some(&b'u') {
                                return Err("dangling high surrogate");
                            }
                            let lo_hex = core::str::from_utf8(&b[i + 8..i + 12])
                                .map_err(|_| "non-ASCII \\u escape")?;
                            let lo = u32::from_str_radix(lo_hex, 16).map_err(|_| "bad \\u")?;
                            if !(0xDC00..=0xDFFF).contains(&lo) {
                                return Err("bad low surrogate");
                            }
                            i += 6;
                            0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00)
                        } else {
                            cp
                        };
                        let ch = char::from_u32(scalar).ok_or("invalid scalar")?;
                        out.push(ch);
                        i += 6;
                    }
                    _ => return Err("unknown escape"),
                }
            }
            _ => {
                // Push the byte directly; we'll let from_utf8_lossy
                // sort it out if there's a malformed sequence. UTF-8
                // is self-synchronising so single-byte push is fine
                // here too (Rust validates on push for `char` but we
                // push bytes via `out.push((byte as char))` only when
                // the byte is ASCII; otherwise we accumulate raw UTF-8
                // sequences below).
                //
                // Simpler: walk the UTF-8 sequence and push as a char.
                let start_u8 = i;
                let first = b[i];
                let extra = if first < 0x80 {
                    0
                } else if first < 0xC0 {
                    return Err("bad utf8 continuation byte");
                } else if first < 0xE0 {
                    1
                } else if first < 0xF0 {
                    2
                } else {
                    3
                };
                if start_u8 + extra >= b.len() {
                    return Err("truncated utf8");
                }
                let slice = &b[start_u8..=start_u8 + extra];
                let s = core::str::from_utf8(slice).map_err(|_| "bad utf8")?;
                for c in s.chars() {
                    out.push(c);
                }
                i = start_u8 + 1 + extra;
            }
        }
    }
    Err("unterminated string")
}

/// Find the matching `}` for the `{` at `bytes[start]`. Returns its index.
fn find_object_end(b: &[u8], start: usize) -> Result<usize, &'static str> {
    if start >= b.len() || b[start] != b'{' {
        return Err("expected '{'");
    }
    let mut depth = 0i32;
    let mut in_str = false;
    let mut i = start;
    while i < b.len() {
        let c = b[i];
        if in_str {
            if c == b'\\' {
                i += 2;
                continue;
            }
            if c == b'"' {
                in_str = false;
            }
        } else {
            match c {
                b'"' => in_str = true,
                b'{' => depth += 1,
                b'}' => {
                    depth -= 1;
                    if depth == 0 {
                        return Ok(i);
                    }
                }
                _ => {}
            }
        }
        i += 1;
    }
    Err("unterminated object")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholder_byte_round_trip() {
        let t = Tokenizer::placeholder();
        let ids = t.encode("hi");
        assert_eq!(t.decode(&ids), "hi");
    }

    #[test]
    fn placeholder_unicode_round_trip() {
        let t = Tokenizer::placeholder();
        let ids = t.encode("héllo 你好");
        assert_eq!(t.decode(&ids), "héllo 你好");
    }

    /// Synthesise a minimal tokenizer.json with vocab + a couple of
    /// merges and verify BPE picks them up.
    #[test]
    fn parses_minimal_tokenizer_json() {
        let json = r#"{
            "version": "1.0",
            "added_tokens": [
                {"id": 0, "content": "<pad>", "special": true},
                {"id": 1, "content": "<eos>", "special": true},
                {"id": 2, "content": "<bos>", "special": true},
                {"id": 3, "content": "<start_of_turn>", "special": true}
            ],
            "model": {
                "type": "BPE",
                "byte_fallback": true,
                "vocab": {
                    "<pad>": 0,
                    "<eos>": 1,
                    "<bos>": 2,
                    "<start_of_turn>": 3,
                    "▁": 10,
                    "h": 11,
                    "e": 12,
                    "l": 13,
                    "o": 14,
                    "▁h": 20,
                    "▁he": 21,
                    "▁hel": 22,
                    "▁hell": 23,
                    "▁hello": 24,
                    "<0x68>": 30,
                    "<0x65>": 31
                },
                "merges": [
                    "▁ h",
                    "▁h e",
                    "▁he l",
                    "▁hel l",
                    "▁hell o"
                ]
            }
        }"#;
        let t = Tokenizer::from_json(json).expect("parse");
        let ids = t.encode("hello");
        // Expect a single merged ▁hello.
        assert_eq!(ids, vec![24]);
    }

    #[test]
    fn falls_back_to_bytes_for_oov() {
        let json = r#"{
            "added_tokens": [
                {"id": 0, "content": "<unk>", "special": true}
            ],
            "model": {
                "type": "BPE",
                "byte_fallback": true,
                "vocab": {
                    "<unk>": 0,
                    "▁": 1,
                    "<0xC3>": 200,
                    "<0xA9>": 201
                },
                "merges": []
            }
        }"#;
        let t = Tokenizer::from_json(json).expect("parse");
        // 'é' is U+00E9, UTF-8 = 0xC3 0xA9.
        let ids = t.encode("é");
        // Expect "▁" + the two byte-fallback ids.
        assert_eq!(ids, vec![1, 200, 201]);
        // Decode round-trips through the bytes.
        assert_eq!(t.decode(&ids), "é");
    }

    #[test]
    fn special_tokens_are_atomic() {
        let json = r#"{
            "added_tokens": [
                {"id": 3, "content": "<start_of_turn>", "special": true}
            ],
            "model": {
                "type": "BPE",
                "byte_fallback": true,
                "vocab": {
                    "<start_of_turn>": 3,
                    "▁": 1,
                    "u": 50,
                    "s": 51,
                    "e": 52,
                    "r": 53,
                    "<0x75>": 60,
                    "<0x73>": 61,
                    "<0x65>": 62,
                    "<0x72>": 63
                },
                "merges": []
            }
        }"#;
        let t = Tokenizer::from_json(json).expect("parse");
        let ids = t.encode("<start_of_turn>user");
        assert_eq!(ids[0], 3);
        // After the special, "user" gets a ▁ prefix and then per-char ids.
        assert_eq!(ids[1], 1); // ▁
        assert_eq!(ids[2..], vec![50, 51, 52, 53]);
    }
}
