//! Block-quantization codecs. The dispatcher [`dequantize_to_f32`] is the
//! single entry point used by the model loader — it takes the GGUF tensor
//! type plus raw bytes and produces an owned `Vec<f32>` of the requested
//! length.

pub mod q4_0;
pub mod q4_k;
pub mod q8_0;

use crate::half::f16_to_f32;

#[derive(Debug)]
pub enum DequantError {
    UnsupportedType(&'static str),
    BadLength { expected: usize, got: usize },
}

impl core::fmt::Display for DequantError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            DequantError::UnsupportedType(t) => write!(f, "unsupported quant type: {t}"),
            DequantError::BadLength { expected, got } => {
                write!(f, "bad source length: expected {expected} bytes, got {got}")
            }
        }
    }
}

/// Identifier mirroring `gguf_loader::GgmlType` but without the dependency.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(non_camel_case_types)] // names mirror the GGML quant scheme tags
pub enum DequantType {
    F32,
    F16,
    BF16,
    Q8_0,
    Q4_0,
    Q4_K,
}

impl DequantType {
    /// Map the GGUF type enum value (matches `gguf_loader::GgmlType as u32`)
    /// to a [`DequantType`]. Returns `None` for types we don't yet dequant.
    pub fn from_ggml(v: u32) -> Option<Self> {
        match v {
            0 => Some(Self::F32),
            1 => Some(Self::F16),
            2 => Some(Self::Q4_0),
            8 => Some(Self::Q8_0),
            12 => Some(Self::Q4_K),
            30 => Some(Self::BF16),
            _ => None,
        }
    }

    pub fn tag(self) -> &'static str {
        match self {
            Self::F32 => "F32",
            Self::F16 => "F16",
            Self::BF16 => "BF16",
            Self::Q8_0 => "Q8_0",
            Self::Q4_0 => "Q4_0",
            Self::Q4_K => "Q4_K",
        }
    }
}

/// Read `numel` scalars from `src`, dequantizing as the format demands.
pub fn dequantize_to_f32(
    ty: DequantType,
    src: &[u8],
    numel: usize,
) -> Result<Vec<f32>, DequantError> {
    match ty {
        DequantType::F32 => {
            let expected = numel * 4;
            if src.len() < expected {
                return Err(DequantError::BadLength { expected, got: src.len() });
            }
            let mut out = Vec::with_capacity(numel);
            for i in 0..numel {
                let b = &src[i * 4..i * 4 + 4];
                out.push(f32::from_le_bytes([b[0], b[1], b[2], b[3]]));
            }
            Ok(out)
        }
        DequantType::F16 => {
            let expected = numel * 2;
            if src.len() < expected {
                return Err(DequantError::BadLength { expected, got: src.len() });
            }
            let mut out = Vec::with_capacity(numel);
            for i in 0..numel {
                let bits = u16::from_le_bytes([src[i * 2], src[i * 2 + 1]]);
                out.push(f16_to_f32(bits));
            }
            Ok(out)
        }
        DequantType::BF16 => {
            let expected = numel * 2;
            if src.len() < expected {
                return Err(DequantError::BadLength { expected, got: src.len() });
            }
            let mut out = Vec::with_capacity(numel);
            for i in 0..numel {
                let bits = u16::from_le_bytes([src[i * 2], src[i * 2 + 1]]);
                out.push(f32::from_bits((bits as u32) << 16));
            }
            Ok(out)
        }
        DequantType::Q8_0 => {
            if numel % q8_0::BLOCK != 0 {
                return Err(DequantError::BadLength {
                    expected: q8_0::BLOCK,
                    got: numel,
                });
            }
            let blocks = numel / q8_0::BLOCK;
            let expected = blocks * q8_0::BYTES_PER_BLOCK;
            if src.len() < expected {
                return Err(DequantError::BadLength { expected, got: src.len() });
            }
            let mut out = vec![0.0f32; numel];
            q8_0::dequantize(&src[..expected], &mut out);
            Ok(out)
        }
        DequantType::Q4_0 => {
            if numel % q4_0::BLOCK != 0 {
                return Err(DequantError::BadLength {
                    expected: q4_0::BLOCK,
                    got: numel,
                });
            }
            let blocks = numel / q4_0::BLOCK;
            let expected = blocks * q4_0::BYTES_PER_BLOCK;
            if src.len() < expected {
                return Err(DequantError::BadLength { expected, got: src.len() });
            }
            let mut out = vec![0.0f32; numel];
            q4_0::dequantize(&src[..expected], &mut out);
            Ok(out)
        }
        DequantType::Q4_K => {
            if numel % q4_k::SUPER_BLOCK != 0 {
                return Err(DequantError::BadLength {
                    expected: q4_k::SUPER_BLOCK,
                    got: numel,
                });
            }
            let supers = numel / q4_k::SUPER_BLOCK;
            let expected = supers * q4_k::BYTES_PER_SUPER_BLOCK;
            if src.len() < expected {
                return Err(DequantError::BadLength { expected, got: src.len() });
            }
            let mut out = vec![0.0f32; numel];
            q4_k::dequantize(&src[..expected], &mut out);
            Ok(out)
        }
    }
}

#[cfg(test)]
mod router_tests {
    use super::*;
    use crate::half::f32_to_f16_bits;

    #[test]
    fn f32_passthrough() {
        let v = [1.0f32, -2.0, 3.5, 0.0];
        let mut bytes = Vec::new();
        for x in &v {
            bytes.extend_from_slice(&x.to_le_bytes());
        }
        let out = dequantize_to_f32(DequantType::F32, &bytes, v.len()).unwrap();
        assert_eq!(out, v);
    }

    #[test]
    fn f16_round_trip() {
        let v = [1.0f32, -2.0, 0.5];
        let mut bytes = Vec::new();
        for x in &v {
            bytes.extend_from_slice(&f32_to_f16_bits(*x).to_le_bytes());
        }
        let out = dequantize_to_f32(DequantType::F16, &bytes, v.len()).unwrap();
        for (a, b) in out.iter().zip(v.iter()) {
            assert!((a - b).abs() < 1e-5);
        }
    }

    #[test]
    fn bf16_drops_low_bits() {
        let x: f32 = 1.0;
        let bits = x.to_bits();
        let bf = (bits >> 16) as u16;
        let bytes = bf.to_le_bytes();
        let out = dequantize_to_f32(DequantType::BF16, &bytes, 1).unwrap();
        assert_eq!(out[0], 1.0);
    }

    #[test]
    fn type_from_ggml_covers_supported_set() {
        assert_eq!(DequantType::from_ggml(0), Some(DequantType::F32));
        assert_eq!(DequantType::from_ggml(1), Some(DequantType::F16));
        assert_eq!(DequantType::from_ggml(2), Some(DequantType::Q4_0));
        assert_eq!(DequantType::from_ggml(8), Some(DequantType::Q8_0));
        assert_eq!(DequantType::from_ggml(12), Some(DequantType::Q4_K));
        assert_eq!(DequantType::from_ggml(30), Some(DequantType::BF16));
        assert_eq!(DequantType::from_ggml(99), None);
    }
}
