//! Q8_0: 32-element blocks. Each block = 2-byte f16 scale + 32 signed i8.
//! `value[i] = scale * q[i]`.

use crate::half::f16_to_f32;

pub const BLOCK: usize = 32;
pub const BYTES_PER_BLOCK: usize = 2 + BLOCK;

pub fn dequantize_block(src: &[u8], dst: &mut [f32]) {
    debug_assert_eq!(src.len(), BYTES_PER_BLOCK);
    debug_assert_eq!(dst.len(), BLOCK);
    let scale_bits = u16::from_le_bytes([src[0], src[1]]);
    let scale = f16_to_f32(scale_bits);
    for i in 0..BLOCK {
        let q = src[2 + i] as i8;
        dst[i] = scale * q as f32;
    }
}

/// Dequantize a contiguous run of Q8_0 blocks.
pub fn dequantize(src: &[u8], dst: &mut [f32]) {
    debug_assert_eq!(src.len() % BYTES_PER_BLOCK, 0);
    debug_assert_eq!(dst.len() % BLOCK, 0);
    debug_assert_eq!(src.len() / BYTES_PER_BLOCK, dst.len() / BLOCK);
    let n_blocks = src.len() / BYTES_PER_BLOCK;
    for b in 0..n_blocks {
        let s = &src[b * BYTES_PER_BLOCK..(b + 1) * BYTES_PER_BLOCK];
        let d = &mut dst[b * BLOCK..(b + 1) * BLOCK];
        dequantize_block(s, d);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    pub(crate) fn pack_block(scale_f32: f32, qs: &[i8; BLOCK]) -> Vec<u8> {
        let bits = crate::half::f32_to_f16_bits(scale_f32);
        let mut out = Vec::with_capacity(BYTES_PER_BLOCK);
        out.extend_from_slice(&bits.to_le_bytes());
        for q in qs {
            out.push(*q as u8);
        }
        out
    }

    #[test]
    fn roundtrip_unit_scale() {
        let qs: [i8; BLOCK] = [
            1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6, -6, 7, -7, 8, -8, 9, -9, 10, -10, 11, -11, 12,
            -12, 13, -13, 14, -14, 15, -15, 16, -16,
        ];
        let src = pack_block(1.0, &qs);
        let mut dst = [0.0f32; BLOCK];
        dequantize_block(&src, &mut dst);
        for i in 0..BLOCK {
            assert!((dst[i] - qs[i] as f32).abs() < 1e-6);
        }
    }

    #[test]
    fn roundtrip_half_scale() {
        let qs: [i8; BLOCK] = [10; BLOCK];
        let src = pack_block(0.5, &qs);
        let mut dst = [0.0f32; BLOCK];
        dequantize_block(&src, &mut dst);
        for d in &dst {
            assert!((d - 5.0).abs() < 1e-6);
        }
    }

    #[test]
    fn multi_block_dequant() {
        let qs: [i8; BLOCK] = [1; BLOCK];
        let one_block = pack_block(2.0, &qs);
        let mut src = Vec::new();
        src.extend(&one_block);
        src.extend(&one_block);
        let mut dst = vec![0.0f32; BLOCK * 2];
        dequantize(&src, &mut dst);
        assert_eq!(
            dst.iter()
                .copied()
                .filter(|&v| (v - 2.0).abs() < 1e-6)
                .count(),
            BLOCK * 2
        );
    }
}
