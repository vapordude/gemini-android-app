//! Hand-rolled `safetensors` reader. Format:
//!
//! ```text
//! [8 bytes little-endian: header_size] [header_size bytes: UTF-8 JSON]
//! [tensor data, packed back-to-back, in offsets given by the header]
//! ```
//!
//! Header JSON shape:
//!
//! ```json
//! {
//!   "tensor_name": {
//!     "dtype": "F32" | "F16" | "BF16" | "I8" | ...,
//!     "shape": [d1, d2, ...],
//!     "data_offsets": [start, end]
//!   },
//!   "__metadata__": { ... }
//! }
//! ```
//!
//! Offsets are RELATIVE to the start of the tensor data section
//! (= 8 + header_size). We translate to absolute positions when slicing.
//!
//! No external crates — JSON header is parsed with a tiny pull-parser
//! tailored to the shape above.

use crate::dtype::Dtype;

#[derive(Debug, Clone)]
pub struct TensorEntry {
    pub name: String,
    pub dtype: Dtype,
    pub shape: Vec<usize>,
    pub start: usize,  // absolute byte offset in the file
    pub end: usize,
}

pub struct SafeTensors<'a> {
    bytes: &'a [u8],
    pub entries: Vec<TensorEntry>,
}

impl<'a> SafeTensors<'a> {
    pub fn parse(bytes: &'a [u8]) -> Result<Self, &'static str> {
        if bytes.len() < 8 { return Err("file too small"); }
        let header_size = u64::from_le_bytes([
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
        ]) as usize;
        if bytes.len() < 8 + header_size { return Err("truncated header"); }
        let header_bytes = &bytes[8..8 + header_size];
        let header = core::str::from_utf8(header_bytes).map_err(|_| "header not UTF-8")?;
        let data_start = 8 + header_size;
        let entries = parse_header(header, data_start)?;
        Ok(SafeTensors { bytes, entries })
    }

    pub fn get(&self, name: &str) -> Option<&TensorEntry> {
        self.entries.iter().find(|e| e.name == name)
    }

    pub fn tensor_bytes(&self, name: &str) -> Option<&[u8]> {
        let e = self.get(name)?;
        Some(&self.bytes[e.start..e.end])
    }

    /// Read an F32 tensor by name. Returns None on missing / wrong-dtype.
    pub fn f32(&self, name: &str) -> Option<Vec<f32>> {
        let e = self.get(name)?;
        if e.dtype != Dtype::F32 { return None; }
        let raw = &self.bytes[e.start..e.end];
        if !raw.len().is_multiple_of(4) { return None; }
        let mut out = Vec::with_capacity(raw.len() / 4);
        for chunk in raw.chunks_exact(4) {
            out.push(f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
        }
        Some(out)
    }

    /// Read a BF16 tensor as raw u16 values (caller converts via bf16_to_f32).
    pub fn bf16(&self, name: &str) -> Option<Vec<u16>> {
        let e = self.get(name)?;
        if e.dtype != Dtype::BF16 { return None; }
        let raw = &self.bytes[e.start..e.end];
        if !raw.len().is_multiple_of(2) { return None; }
        let mut out = Vec::with_capacity(raw.len() / 2);
        for chunk in raw.chunks_exact(2) {
            out.push(u16::from_le_bytes([chunk[0], chunk[1]]));
        }
        Some(out)
    }

    /// Number of scalar elements in `name`. Computed from the declared
    /// shape, not from the on-disk byte length — the loader uses this to
    /// pre-size buffers before the (potentially streaming) dequant.
    pub fn numel(&self, name: &str) -> Option<usize> {
        let e = self.get(name)?;
        Some(e.shape.iter().product())
    }

    /// Read any supported dtype and return an owned `Vec<f32>`. This is
    /// the load-time path used by [`crate::SafeTensors`] consumers that
    /// want a single F32 representation regardless of the on-disk
    /// encoding. Returns `None` when the tensor is missing or the dtype
    /// isn't yet supported (only Q4_K_M from gemma4-quant — that path
    /// goes through [`as_q4km_bytes`] instead, since it stays packed).
    pub fn as_f32(&self, name: &str) -> Option<Vec<f32>> {
        let e = self.get(name)?;
        let raw = &self.bytes[e.start..e.end];
        let n: usize = e.shape.iter().product();
        match e.dtype {
            Dtype::F32 => {
                if raw.len() < n * 4 {
                    return None;
                }
                let mut out = Vec::with_capacity(n);
                for chunk in raw.chunks_exact(4).take(n) {
                    out.push(f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
                }
                Some(out)
            }
            Dtype::BF16 => {
                if raw.len() < n * 2 {
                    return None;
                }
                let mut out = Vec::with_capacity(n);
                for chunk in raw.chunks_exact(2).take(n) {
                    let bits = u16::from_le_bytes([chunk[0], chunk[1]]);
                    out.push(crate::dtype::bf16_to_f32(bits));
                }
                Some(out)
            }
            Dtype::F16 => {
                if raw.len() < n * 2 {
                    return None;
                }
                let mut out = Vec::with_capacity(n);
                for chunk in raw.chunks_exact(2).take(n) {
                    let bits = u16::from_le_bytes([chunk[0], chunk[1]]);
                    out.push(crate::dtype::f16_to_f32(bits));
                }
                Some(out)
            }
            // Int8 carries its own scale stored as a sibling tensor or
            // in a `scale_inv` entry; without that companion, we can't
            // turn it into F32. The loader handles INT8 weights through
            // gemma4-quant directly.
            Dtype::Int8 => None,
            Dtype::Q4KM => None,
        }
    }

    /// Raw bytes for the Q4_K_M payload of `name`, if it's stored as a
    /// raw blob (custom packing — see gemma4-quant). Used by the
    /// quantized-matmul path that operates on the packed form directly.
    pub fn as_q4km_bytes(&self, name: &str) -> Option<&[u8]> {
        let e = self.get(name)?;
        if e.dtype != Dtype::Q4KM {
            return None;
        }
        Some(&self.bytes[e.start..e.end])
    }
}

/// Tiny header parser. The JSON we expect is a flat object whose values are
/// `{dtype, shape, data_offsets}`. We hand-roll because pulling in
/// `serde_json` would violate the no-deps constraint.
fn parse_header(json: &str, data_start: usize) -> Result<Vec<TensorEntry>, &'static str> {
    let mut entries = Vec::new();
    let s = json.trim();
    let s = strip_outer_braces(s).ok_or("expected JSON object")?;
    let mut i = 0;
    let bytes = s.as_bytes();
    while i < bytes.len() {
        i = skip_ws(bytes, i);
        if i >= bytes.len() { break; }
        if bytes[i] == b',' { i += 1; continue; }
        let (name, next) = parse_string(bytes, i)?;
        i = next;
        i = skip_ws(bytes, i);
        if i >= bytes.len() || bytes[i] != b':' { return Err("expected ':'"); }
        i += 1;
        i = skip_ws(bytes, i);
        // Skip __metadata__ — we don't use it.
        if name == "__metadata__" {
            let end = find_object_end(bytes, i)?;
            i = end + 1;
            continue;
        }
        let entry_str = read_object_string(bytes, i)?;
        let entry = parse_entry(name.clone(), &entry_str, data_start)?;
        entries.push(entry);
        i += entry_str.len();
    }
    Ok(entries)
}

fn strip_outer_braces(s: &str) -> Option<&str> {
    let s = s.trim();
    let s = s.strip_prefix('{')?.strip_suffix('}')?;
    Some(s)
}

fn skip_ws(b: &[u8], mut i: usize) -> usize {
    while i < b.len() && (b[i] == b' ' || b[i] == b'\t' || b[i] == b'\n' || b[i] == b'\r') { i += 1; }
    i
}

fn parse_string(b: &[u8], start: usize) -> Result<(String, usize), &'static str> {
    if start >= b.len() || b[start] != b'"' { return Err("expected '\"'"); }
    let mut out = String::new();
    let mut i = start + 1;
    while i < b.len() {
        let c = b[i];
        if c == b'\\' && i + 1 < b.len() {
            out.push(b[i + 1] as char);
            i += 2;
        } else if c == b'"' {
            return Ok((out, i + 1));
        } else {
            out.push(c as char);
            i += 1;
        }
    }
    Err("unterminated string")
}

fn find_object_end(b: &[u8], start: usize) -> Result<usize, &'static str> {
    if start >= b.len() || b[start] != b'{' { return Err("expected '{'"); }
    let mut depth = 0;
    let mut in_str = false;
    let mut i = start;
    while i < b.len() {
        let c = b[i];
        if in_str {
            if c == b'\\' { i += 2; continue; }
            if c == b'"' { in_str = false; }
        } else {
            if c == b'"' { in_str = true; }
            else if c == b'{' { depth += 1; }
            else if c == b'}' { depth -= 1; if depth == 0 { return Ok(i); } }
        }
        i += 1;
    }
    Err("unterminated object")
}

fn read_object_string(b: &[u8], start: usize) -> Result<String, &'static str> {
    let end = find_object_end(b, start)?;
    Ok(core::str::from_utf8(&b[start..=end]).map_err(|_| "non-UTF-8")?.to_string())
}

fn parse_entry(name: String, json: &str, data_start: usize) -> Result<TensorEntry, &'static str> {
    let dtype = read_field(json, "dtype").ok_or("missing dtype")?;
    let dtype = match dtype.as_str() {
        "F32" => Dtype::F32,
        "F16" => Dtype::F16,
        "BF16" => Dtype::BF16,
        "I8" | "INT8" => Dtype::Int8,
        _ => return Err("unsupported dtype"),
    };
    let shape = read_array_field(json, "shape").ok_or("missing shape")?;
    let offsets = read_array_field(json, "data_offsets").ok_or("missing data_offsets")?;
    if offsets.len() != 2 { return Err("data_offsets must be [start, end]"); }
    Ok(TensorEntry {
        name,
        dtype,
        shape: shape.into_iter().map(|v| v as usize).collect(),
        start: data_start + offsets[0] as usize,
        end: data_start + offsets[1] as usize,
    })
}

fn read_field(json: &str, key: &str) -> Option<String> {
    let needle = format!("\"{key}\"");
    let i = json.find(&needle)?;
    let after = &json[i + needle.len()..];
    let after = after.trim_start().strip_prefix(':')?.trim_start();
    let bytes = after.as_bytes();
    let (s, _) = parse_string(bytes, 0).ok()?;
    Some(s)
}

fn read_array_field(json: &str, key: &str) -> Option<Vec<i64>> {
    let needle = format!("\"{key}\"");
    let i = json.find(&needle)?;
    let after = &json[i + needle.len()..];
    let after = after.trim_start().strip_prefix(':')?.trim_start();
    let after = after.strip_prefix('[')?;
    let end = after.find(']')?;
    let body = &after[..end];
    let mut out = Vec::new();
    for part in body.split(',') {
        let trimmed = part.trim();
        if trimmed.is_empty() { continue; }
        out.push(trimmed.parse::<i64>().ok()?);
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn build_minimal_safetensors() -> Vec<u8> {
        // One F32 tensor "x" of shape [2] with values [1.0, 2.0].
        let payload: Vec<u8> = [1.0f32, 2.0f32]
            .iter().flat_map(|v| v.to_le_bytes()).collect();
        let header = r#"{"x":{"dtype":"F32","shape":[2],"data_offsets":[0,8]}}"#;
        let mut buf = Vec::new();
        buf.extend_from_slice(&(header.len() as u64).to_le_bytes());
        buf.extend_from_slice(header.as_bytes());
        buf.extend_from_slice(&payload);
        buf
    }

    #[test]
    fn parses_minimal_file() {
        let bytes = build_minimal_safetensors();
        let st = SafeTensors::parse(&bytes).expect("parse");
        assert_eq!(st.entries.len(), 1);
        assert_eq!(st.entries[0].name, "x");
        assert_eq!(st.entries[0].dtype, Dtype::F32);
        assert_eq!(st.entries[0].shape, vec![2]);
        let x = st.f32("x").expect("f32");
        assert_eq!(x, vec![1.0, 2.0]);
    }

    #[test]
    fn rejects_truncated_header() {
        let mut bytes = build_minimal_safetensors();
        bytes.truncate(20);
        assert!(SafeTensors::parse(&bytes).is_err());
    }
}
