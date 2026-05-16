//! Pure-Rust GGUF v3 parser. File format only — no third-party deps.
//! Reads everything from the file so the runtime never guesses: tokenizer
//! (vocab, merges, special tokens, whitespace marker), per-tensor type,
//! architecture-specific params (RoPE base, sliding window, etc.).

#![deny(unsafe_op_in_unsafe_fn)]

use std::fs::File;
use std::io::{self, BufReader, Read, Seek};
use std::path::Path;

const MAGIC: u32 = 0x46554747; // "GGUF" little-endian
const SUPPORTED_VERSION: u32 = 3;

#[derive(Debug)]
pub enum LoadError {
    Io(io::Error),
    BadMagic,
    UnsupportedVersion(u32),
    UnknownValueType(u32),
    UnknownTensorType(u32),
    BadString,
    Truncated,
}

impl From<io::Error> for LoadError {
    fn from(e: io::Error) -> Self {
        LoadError::Io(e)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(non_camel_case_types)]
pub enum GgmlType {
    F32 = 0,
    F16 = 1,
    Q4_0 = 2,
    Q4_1 = 3,
    Q5_0 = 6,
    Q5_1 = 7,
    Q8_0 = 8,
    Q8_1 = 9,
    Q2_K = 10,
    Q3_K = 11,
    Q4_K = 12,
    Q5_K = 13,
    Q6_K = 14,
    Q8_K = 15,
    BF16 = 30,
}

impl GgmlType {
    pub fn from_u32(v: u32) -> Result<Self, LoadError> {
        Ok(match v {
            0 => Self::F32,
            1 => Self::F16,
            2 => Self::Q4_0,
            3 => Self::Q4_1,
            6 => Self::Q5_0,
            7 => Self::Q5_1,
            8 => Self::Q8_0,
            9 => Self::Q8_1,
            10 => Self::Q2_K,
            11 => Self::Q3_K,
            12 => Self::Q4_K,
            13 => Self::Q5_K,
            14 => Self::Q6_K,
            15 => Self::Q8_K,
            30 => Self::BF16,
            other => return Err(LoadError::UnknownTensorType(other)),
        })
    }

    pub fn tag(self) -> &'static str {
        match self {
            Self::F32 => "F32",
            Self::F16 => "F16",
            Self::Q4_0 => "Q4_0",
            Self::Q4_1 => "Q4_1",
            Self::Q5_0 => "Q5_0",
            Self::Q5_1 => "Q5_1",
            Self::Q8_0 => "Q8_0",
            Self::Q8_1 => "Q8_1",
            Self::Q2_K => "Q2_K",
            Self::Q3_K => "Q3_K",
            Self::Q4_K => "Q4_K",
            Self::Q5_K => "Q5_K",
            Self::Q6_K => "Q6_K",
            Self::Q8_K => "Q8_K",
            Self::BF16 => "BF16",
        }
    }
}

#[derive(Debug, Clone)]
pub enum MetaValue {
    U8(u8),
    I8(i8),
    U16(u16),
    I16(i16),
    U32(u32),
    I32(i32),
    F32(f32),
    Bool(bool),
    String(String),
    Array(Vec<MetaValue>),
    U64(u64),
    I64(i64),
    F64(f64),
}

impl MetaValue {
    pub fn as_string(&self) -> Option<&str> {
        if let MetaValue::String(s) = self {
            Some(s)
        } else {
            None
        }
    }
    pub fn as_u32(&self) -> Option<u32> {
        match self {
            MetaValue::U32(v) => Some(*v),
            MetaValue::I32(v) if *v >= 0 => Some(*v as u32),
            MetaValue::U64(v) if *v <= u32::MAX as u64 => Some(*v as u32),
            _ => None,
        }
    }
    pub fn as_f32(&self) -> Option<f32> {
        match self {
            MetaValue::F32(v) => Some(*v),
            MetaValue::F64(v) => Some(*v as f32),
            _ => None,
        }
    }
    pub fn as_array(&self) -> Option<&[MetaValue]> {
        if let MetaValue::Array(v) = self {
            Some(v)
        } else {
            None
        }
    }
}

#[derive(Debug, Clone)]
pub struct TensorInfo {
    pub name: String,
    pub dims: Vec<u64>,
    pub ggml_type: GgmlType,
    pub offset: u64,
}

impl TensorInfo {
    /// Total scalar count = product of dims.
    pub fn numel(&self) -> usize {
        self.dims.iter().fold(1usize, |acc, &d| acc * d as usize)
    }

    /// Bytes occupied on disk for this tensor's raw payload. Block-quants
    /// pack 32 or 256 scalars per fixed-size byte block; F-floats are one
    /// scalar per (2 or 4) bytes.
    pub fn byte_size(&self) -> usize {
        let numel = self.numel();
        match self.ggml_type {
            GgmlType::F32 => numel * 4,
            GgmlType::F16 | GgmlType::BF16 => numel * 2,
            GgmlType::Q8_0 => (numel / 32) * (2 + 32),
            GgmlType::Q4_0 => (numel / 32) * (2 + 16),
            GgmlType::Q4_1 => (numel / 32) * (2 + 2 + 16),
            GgmlType::Q5_0 => (numel / 32) * (2 + 4 + 16),
            GgmlType::Q5_1 => (numel / 32) * (2 + 2 + 4 + 16),
            GgmlType::Q8_1 => (numel / 32) * (4 + 4 + 32),
            GgmlType::Q2_K => (numel / 256) * (16 + 64 + 2 + 2),
            GgmlType::Q3_K => (numel / 256) * (32 + 64 + 12 + 2),
            GgmlType::Q4_K => (numel / 256) * (2 + 2 + 12 + 128),
            GgmlType::Q5_K => (numel / 256) * (2 + 2 + 12 + 32 + 128),
            GgmlType::Q6_K => (numel / 256) * (128 + 64 + 16 + 2),
            GgmlType::Q8_K => (numel / 256) * (4 + 256 + 4 * (256 / 16)),
        }
    }
}

#[derive(Debug)]
pub struct GgufFile {
    pub version: u32,
    pub metadata: Vec<(String, MetaValue)>,
    pub tensors: Vec<TensorInfo>,
    pub tensor_data_start: u64,
}

impl GgufFile {
    pub fn arch_tag(&self) -> Option<&str> {
        self.metadata
            .iter()
            .find(|(k, _)| k == "general.architecture")
            .and_then(|(_, v)| v.as_string())
    }

    pub fn get(&self, key: &str) -> Option<&MetaValue> {
        self.metadata.iter().find(|(k, _)| k == key).map(|(_, v)| v)
    }

    pub fn tensor(&self, name: &str) -> Option<&TensorInfo> {
        self.tensors.iter().find(|t| t.name == name)
    }
}

/// In-memory copy of a GGUF file plus the parsed header. The tensor
/// payload sits at `bytes[tensor_data_start..]`, with each tensor's
/// region starting at `bytes[tensor_data_start + tensor.offset]` and
/// running for `tensor.byte_size()` bytes.
///
/// Loading the whole file into a `Vec<u8>` is wasteful for multi-GB
/// quants, but it sidesteps the mmap lifetime ceremony and works
/// identically on Android and host. The real production path is a
/// memory-mapped view; this struct's API is shaped so that swap is a
/// one-call substitution.
pub struct GgufBytes {
    pub file: GgufFile,
    pub bytes: Vec<u8>,
}

impl GgufBytes {
    pub fn read(path: &Path) -> Result<Self, LoadError> {
        let bytes = std::fs::read(path)?;
        // Re-parse the header from the in-memory buffer rather than
        // re-opening the file. Simpler than juggling a Cursor.
        let file = read(path)?;
        Ok(Self { file, bytes })
    }

    /// Raw on-disk slice for `name`, or `None` if the tensor is missing
    /// or its declared region runs past the file. Callers feed this
    /// directly into `tensor_core::quant::dequantize_to_f32`.
    pub fn tensor_bytes(&self, name: &str) -> Option<&[u8]> {
        let t = self.file.tensor(name)?;
        let start = self.file.tensor_data_start as usize + t.offset as usize;
        let len = t.byte_size();
        let end = start.checked_add(len)?;
        if end > self.bytes.len() {
            return None;
        }
        Some(&self.bytes[start..end])
    }
}

pub fn read(path: &Path) -> Result<GgufFile, LoadError> {
    let f = File::open(path)?;
    let mut r = BufReader::new(f);
    let magic = read_u32(&mut r)?;
    if magic != MAGIC {
        return Err(LoadError::BadMagic);
    }
    let version = read_u32(&mut r)?;
    if version != SUPPORTED_VERSION {
        return Err(LoadError::UnsupportedVersion(version));
    }
    let tensor_count = read_u64(&mut r)?;
    let metadata_count = read_u64(&mut r)?;

    let mut metadata = Vec::with_capacity(metadata_count as usize);
    for _ in 0..metadata_count {
        let key = read_string(&mut r)?;
        let value = read_meta_value(&mut r)?;
        metadata.push((key, value));
    }

    let mut tensors = Vec::with_capacity(tensor_count as usize);
    for _ in 0..tensor_count {
        let name = read_string(&mut r)?;
        let n_dims = read_u32(&mut r)?;
        let mut dims = Vec::with_capacity(n_dims as usize);
        for _ in 0..n_dims {
            dims.push(read_u64(&mut r)?);
        }
        let type_id = read_u32(&mut r)?;
        let ggml_type = GgmlType::from_u32(type_id)?;
        let offset = read_u64(&mut r)?;
        tensors.push(TensorInfo {
            name,
            dims,
            ggml_type,
            offset,
        });
    }

    // Align tensor data start to the alignment specified in metadata (default 32).
    let alignment = metadata
        .iter()
        .find(|(k, _)| k == "general.alignment")
        .and_then(|(_, v)| v.as_u32())
        .unwrap_or(32) as u64;
    let pos = r.stream_position()?;
    let tensor_data_start = align_up(pos, alignment);

    Ok(GgufFile {
        version,
        metadata,
        tensors,
        tensor_data_start,
    })
}

fn align_up(v: u64, a: u64) -> u64 {
    if a == 0 {
        v
    } else {
        v.div_ceil(a) * a
    }
}

fn read_u8<R: Read>(r: &mut R) -> Result<u8, LoadError> {
    let mut b = [0u8; 1];
    r.read_exact(&mut b)?;
    Ok(b[0])
}
fn read_u16<R: Read>(r: &mut R) -> Result<u16, LoadError> {
    let mut b = [0u8; 2];
    r.read_exact(&mut b)?;
    Ok(u16::from_le_bytes(b))
}
fn read_u32<R: Read>(r: &mut R) -> Result<u32, LoadError> {
    let mut b = [0u8; 4];
    r.read_exact(&mut b)?;
    Ok(u32::from_le_bytes(b))
}
fn read_u64<R: Read>(r: &mut R) -> Result<u64, LoadError> {
    let mut b = [0u8; 8];
    r.read_exact(&mut b)?;
    Ok(u64::from_le_bytes(b))
}
fn read_i8<R: Read>(r: &mut R) -> Result<i8, LoadError> {
    Ok(read_u8(r)? as i8)
}
fn read_i16<R: Read>(r: &mut R) -> Result<i16, LoadError> {
    Ok(read_u16(r)? as i16)
}
fn read_i32<R: Read>(r: &mut R) -> Result<i32, LoadError> {
    Ok(read_u32(r)? as i32)
}
fn read_i64<R: Read>(r: &mut R) -> Result<i64, LoadError> {
    Ok(read_u64(r)? as i64)
}
fn read_f32<R: Read>(r: &mut R) -> Result<f32, LoadError> {
    let mut b = [0u8; 4];
    r.read_exact(&mut b)?;
    Ok(f32::from_le_bytes(b))
}
fn read_f64<R: Read>(r: &mut R) -> Result<f64, LoadError> {
    let mut b = [0u8; 8];
    r.read_exact(&mut b)?;
    Ok(f64::from_le_bytes(b))
}
fn read_bool<R: Read>(r: &mut R) -> Result<bool, LoadError> {
    Ok(read_u8(r)? != 0)
}

fn read_string<R: Read>(r: &mut R) -> Result<String, LoadError> {
    let len = read_u64(r)? as usize;
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf)?;
    String::from_utf8(buf).map_err(|_| LoadError::BadString)
}

fn read_meta_value<R: Read>(r: &mut R) -> Result<MetaValue, LoadError> {
    let type_id = read_u32(r)?;
    read_meta_value_typed(r, type_id)
}

fn read_meta_value_typed<R: Read>(r: &mut R, type_id: u32) -> Result<MetaValue, LoadError> {
    Ok(match type_id {
        0 => MetaValue::U8(read_u8(r)?),
        1 => MetaValue::I8(read_i8(r)?),
        2 => MetaValue::U16(read_u16(r)?),
        3 => MetaValue::I16(read_i16(r)?),
        4 => MetaValue::U32(read_u32(r)?),
        5 => MetaValue::I32(read_i32(r)?),
        6 => MetaValue::F32(read_f32(r)?),
        7 => MetaValue::Bool(read_bool(r)?),
        8 => MetaValue::String(read_string(r)?),
        9 => {
            let inner_type = read_u32(r)?;
            let n = read_u64(r)? as usize;
            let mut out = Vec::with_capacity(n);
            for _ in 0..n {
                out.push(read_meta_value_typed(r, inner_type)?);
            }
            MetaValue::Array(out)
        }
        10 => MetaValue::U64(read_u64(r)?),
        11 => MetaValue::I64(read_i64(r)?),
        12 => MetaValue::F64(read_f64(r)?),
        other => return Err(LoadError::UnknownValueType(other)),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn synth_gguf(arch: &str, extra_kv: &[(&str, MetaValue)], tensors: &[TensorInfo]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&MAGIC.to_le_bytes());
        out.extend_from_slice(&3u32.to_le_bytes());
        out.extend_from_slice(&(tensors.len() as u64).to_le_bytes());
        let kv_count = 1 + extra_kv.len() as u64;
        out.extend_from_slice(&kv_count.to_le_bytes());
        write_kv(
            &mut out,
            "general.architecture",
            &MetaValue::String(arch.to_string()),
        );
        for (k, v) in extra_kv {
            write_kv(&mut out, k, v);
        }
        for t in tensors {
            write_string(&mut out, &t.name);
            out.extend_from_slice(&(t.dims.len() as u32).to_le_bytes());
            for d in &t.dims {
                out.extend_from_slice(&d.to_le_bytes());
            }
            out.extend_from_slice(&(t.ggml_type as u32).to_le_bytes());
            out.extend_from_slice(&t.offset.to_le_bytes());
        }
        out
    }

    fn write_string(out: &mut Vec<u8>, s: &str) {
        let bytes = s.as_bytes();
        out.extend_from_slice(&(bytes.len() as u64).to_le_bytes());
        out.extend_from_slice(bytes);
    }

    fn write_kv(out: &mut Vec<u8>, k: &str, v: &MetaValue) {
        write_string(out, k);
        match v {
            MetaValue::String(s) => {
                out.extend_from_slice(&8u32.to_le_bytes());
                write_string(out, s);
            }
            MetaValue::U32(n) => {
                out.extend_from_slice(&4u32.to_le_bytes());
                out.extend_from_slice(&n.to_le_bytes());
            }
            MetaValue::F32(n) => {
                out.extend_from_slice(&6u32.to_le_bytes());
                out.extend_from_slice(&n.to_le_bytes());
            }
            MetaValue::Array(items) => {
                out.extend_from_slice(&9u32.to_le_bytes());
                // Assume homogeneous string array for our tests.
                out.extend_from_slice(&8u32.to_le_bytes());
                out.extend_from_slice(&(items.len() as u64).to_le_bytes());
                for it in items {
                    if let MetaValue::String(s) = it {
                        write_string(out, s);
                    }
                }
            }
            _ => unimplemented!("test fixture only handles strings/u32/f32/array-of-string"),
        }
    }

    #[test]
    fn parses_minimal_header() {
        let bytes = synth_gguf("gemma4", &[], &[]);
        let tmp = tempfile_path("min");
        std::fs::write(&tmp, &bytes).unwrap();
        let g = read(&tmp).unwrap();
        assert_eq!(g.version, 3);
        assert_eq!(g.arch_tag(), Some("gemma4"));
        assert!(g.tensors.is_empty());
    }

    #[test]
    fn reads_extra_metadata() {
        let bytes = synth_gguf(
            "gemma4",
            &[
                ("gemma4.attention.head_count", MetaValue::U32(8)),
                ("gemma4.rope.freq_base", MetaValue::F32(10000.0)),
            ],
            &[],
        );
        let tmp = tempfile_path("meta");
        std::fs::write(&tmp, &bytes).unwrap();
        let g = read(&tmp).unwrap();
        assert_eq!(
            g.get("gemma4.attention.head_count")
                .and_then(|v| v.as_u32()),
            Some(8)
        );
        assert_eq!(
            g.get("gemma4.rope.freq_base").and_then(|v| v.as_f32()),
            Some(10000.0)
        );
    }

    #[test]
    fn reads_tensors() {
        let tensors = vec![
            TensorInfo {
                name: "token_embd.weight".to_string(),
                dims: vec![2048, 32000],
                ggml_type: GgmlType::Q8_0,
                offset: 0,
            },
            TensorInfo {
                name: "output_norm.weight".to_string(),
                dims: vec![2048],
                ggml_type: GgmlType::F32,
                offset: 1024,
            },
        ];
        let bytes = synth_gguf("gemma4", &[], &tensors);
        let tmp = tempfile_path("tensors");
        std::fs::write(&tmp, &bytes).unwrap();
        let g = read(&tmp).unwrap();
        assert_eq!(g.tensors.len(), 2);
        assert_eq!(g.tensors[0].name, "token_embd.weight");
        assert_eq!(g.tensors[0].ggml_type, GgmlType::Q8_0);
        assert_eq!(g.tensors[1].ggml_type, GgmlType::F32);
    }

    #[test]
    fn rejects_bad_magic() {
        let mut bytes = synth_gguf("gemma4", &[], &[]);
        bytes[0] = b'X';
        let tmp = tempfile_path("badmagic");
        std::fs::write(&tmp, &bytes).unwrap();
        assert!(matches!(read(&tmp), Err(LoadError::BadMagic)));
    }

    fn tempfile_path(suffix: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir();
        dir.join(format!("gguf-test-{}-{}.bin", suffix, std::process::id()))
    }
}
