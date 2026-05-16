//! Symmetric quantization primitives.
//!
//! Math spec (Qwen memo):
//!
//! ```text
//!   q = clip(round(x / s), -2^{b-1}, 2^{b-1} - 1)
//!   s = max(|x|) / (2^{b-1} - 1)
//!
//!   Σ x·w ≈ s_x · s_w · Σ q_x·q_w   (INT32 accumulator)
//! ```
//!
//! INT8: signed, single-scale per tensor (or per channel — caller's
//! choice). Q4_K_M: 32-element blocks; each block has 16 bytes of
//! packed nibbles + a single f32 scale stored alongside. Decode-on-the-fly
//! during matmul.
//!
//! No external dependencies.

#![forbid(unsafe_op_in_unsafe_fn)]

use gemma4_core::dtype::Dtype;

pub fn dtype_bytes(d: Dtype) -> Option<usize> {
    match d {
        Dtype::F32 => Some(4),
        Dtype::F16 | Dtype::BF16 => Some(2),
        Dtype::Int8 => Some(1),
        Dtype::Q4KM => None, // variable; see [`q4km_block_bytes`].
    }
}

/// One Q4_K block holds 32 weights as packed 4-bit values (16 bytes) plus
/// a single f32 scale (4 bytes). Total: 20 bytes per 32 weights → 5 bits
/// per weight on average (4 data bits + 1 metadata bit).
pub const Q4KM_BLOCK_SIZE: usize = 32;
pub fn q4km_block_bytes() -> usize { 16 + 4 }

/// Symmetric INT8 quantization: produces (quantized, scale) such that
/// `x_i ≈ q_i · scale`. Per-tensor scale (single value).
pub fn quantize_symmetric_i8(x: &[f32]) -> (Vec<i8>, f32) {
    let abs_max = x.iter().fold(0.0_f32, |m, &v| m.max(v.abs()));
    if abs_max == 0.0 {
        return (vec![0i8; x.len()], 1.0);
    }
    let scale = abs_max / 127.0;
    let inv = 1.0 / scale;
    let mut out = Vec::with_capacity(x.len());
    for &v in x {
        let q = (v * inv).round().clamp(-128.0, 127.0) as i8;
        out.push(q);
    }
    (out, scale)
}

pub fn dequantize_i8(q: &[i8], scale: f32) -> Vec<f32> {
    q.iter().map(|&v| (v as f32) * scale).collect()
}

/// INT8 dot product with INT32 accumulator and scale correction at the end.
/// This is the per-Qwen-memo formulation: `Σ x·w ≈ s_x · s_w · Σ q_x·q_w`.
pub fn dot_i8(qx: &[i8], qw: &[i8], scale_x: f32, scale_w: f32) -> f32 {
    assert_eq!(qx.len(), qw.len());
    let mut acc: i64 = 0; // wider than i32 to be safe for long vectors
    for i in 0..qx.len() {
        acc += (qx[i] as i64) * (qw[i] as i64);
    }
    (acc as f32) * scale_x * scale_w
}

/// Pack a row of f32 into Q4_K blocks. Each 32-element block becomes
/// 16 bytes of packed 4-bit values (low nibble = even index, high =
/// odd) + 4 bytes of f32 scale. Tail elements (`x.len() % 32`) are
/// dropped to keep the block invariant.
pub fn quantize_q4km(x: &[f32]) -> Vec<u8> {
    let n_blocks = x.len() / Q4KM_BLOCK_SIZE;
    let mut out = Vec::with_capacity(n_blocks * q4km_block_bytes());
    for b in 0..n_blocks {
        let block = &x[b * Q4KM_BLOCK_SIZE..(b + 1) * Q4KM_BLOCK_SIZE];
        let abs_max = block.iter().fold(0.0_f32, |m, &v| m.max(v.abs()));
        let scale = if abs_max == 0.0 { 1.0 } else { abs_max / 7.0 }; // 4-bit signed: -8..7
        let inv = 1.0 / scale;
        for chunk in block.chunks(2) {
            let lo = (chunk[0] * inv).round().clamp(-8.0, 7.0) as i32;
            let hi = (chunk[1] * inv).round().clamp(-8.0, 7.0) as i32;
            let packed = ((lo & 0xF) as u8) | (((hi & 0xF) as u8) << 4);
            out.push(packed);
        }
        out.extend_from_slice(&scale.to_le_bytes());
    }
    out
}

pub fn dequantize_q4km(bytes: &[u8]) -> Vec<f32> {
    let bb = q4km_block_bytes();
    if !bytes.len().is_multiple_of(bb) { return Vec::new(); }
    let n_blocks = bytes.len() / bb;
    let mut out = Vec::with_capacity(n_blocks * Q4KM_BLOCK_SIZE);
    for b in 0..n_blocks {
        let block = &bytes[b * bb..(b + 1) * bb];
        let scale_bytes = &block[16..20];
        let scale = f32::from_le_bytes([scale_bytes[0], scale_bytes[1], scale_bytes[2], scale_bytes[3]]);
        for &byte in block.iter().take(16) {
            let lo = ((byte & 0x0F) as i8).wrapping_sub(if byte & 0x08 != 0 { 16 } else { 0 });
            let hi = (((byte >> 4) & 0x0F) as i8).wrapping_sub(if (byte >> 4) & 0x08 != 0 { 16 } else { 0 });
            out.push((lo as f32) * scale);
            out.push((hi as f32) * scale);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn int8_round_trip_within_quantization_error() {
        let x = vec![0.1_f32, -0.5, 1.0, -1.0, 0.3, 0.7, -0.2];
        let (q, s) = quantize_symmetric_i8(&x);
        let back = dequantize_i8(&q, s);
        // Worst-case error ≤ s/2 (rounding bound).
        for (orig, recovered) in x.iter().zip(back.iter()) {
            assert!((orig - recovered).abs() <= s / 2.0 + 1e-6,
                "out of bounds: orig={orig} recovered={recovered} s={s}");
        }
    }

    #[test]
    fn int8_dot_matches_f32_within_error() {
        let x = vec![0.1_f32, -0.5, 1.0, -1.0];
        let w = vec![0.2_f32, 0.6, -0.3, 0.9];
        let (qx, sx) = quantize_symmetric_i8(&x);
        let (qw, sw) = quantize_symmetric_i8(&w);
        let q_dot = dot_i8(&qx, &qw, sx, sw);
        let f_dot: f32 = x.iter().zip(&w).map(|(a, b)| a * b).sum();
        // Bound: per-element error * dim ≤ scale_x * scale_w * dim / 4 (rough).
        let bound = sx * sw * (x.len() as f32) / 2.0;
        assert!((q_dot - f_dot).abs() < bound + 1e-3,
            "q_dot={q_dot} f_dot={f_dot} bound={bound}");
    }

    #[test]
    fn q4km_round_trip_one_block() {
        let mut x: Vec<f32> = (0..32).map(|i| (i as f32 - 16.0) * 0.05).collect();
        // Ensure non-zero abs_max so scale isn't degenerate.
        x[0] = 1.0;
        let q = quantize_q4km(&x);
        assert_eq!(q.len(), q4km_block_bytes());
        let back = dequantize_q4km(&q);
        assert_eq!(back.len(), 32);
        // Q4 error bound ≈ scale/2 ≈ max_abs/14.
        let bound = 1.0_f32 / 7.0 / 2.0 + 1e-3;
        for (a, b) in x.iter().zip(back.iter()) {
            let d = (a - b).abs();
            assert!(d < bound, "diff {d} exceeds bound {bound}");
        }
    }

    #[test]
    fn dtype_bytes_returns_size() {
        assert_eq!(dtype_bytes(Dtype::F32), Some(4));
        assert_eq!(dtype_bytes(Dtype::BF16), Some(2));
        assert_eq!(dtype_bytes(Dtype::Int8), Some(1));
        assert_eq!(dtype_bytes(Dtype::Q4KM), None);
    }
}
