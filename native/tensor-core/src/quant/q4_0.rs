//! Q4_0: 32-element blocks. Each block = 2-byte f16 scale + 16 packed nibbles.
//! Low nibble of byte i holds element i, high nibble holds element i+16.
//! Each nibble is in [0, 15]; the dequantized value is `scale * (q - 8)`.

use crate::half::f16_to_f32;

pub const BLOCK: usize = 32;
pub const BYTES_PER_BLOCK: usize = 2 + BLOCK / 2;

pub fn dequantize_block(src: &[u8], dst: &mut [f32]) {
    debug_assert_eq!(src.len(), BYTES_PER_BLOCK);
    debug_assert_eq!(dst.len(), BLOCK);
    let scale = f16_to_f32(u16::from_le_bytes([src[0], src[1]]));
    for i in 0..BLOCK / 2 {
        let byte = src[2 + i];
        let lo = (byte & 0x0f) as i32 - 8;
        let hi = ((byte >> 4) & 0x0f) as i32 - 8;
        dst[i] = scale * lo as f32;
        dst[i + BLOCK / 2] = scale * hi as f32;
    }
}

pub fn dequantize(src: &[u8], dst: &mut [f32]) {
    debug_assert_eq!(src.len() % BYTES_PER_BLOCK, 0);
    debug_assert_eq!(dst.len() % BLOCK, 0);
    debug_assert_eq!(src.len() / BYTES_PER_BLOCK, dst.len() / BLOCK);
    let n = src.len() / BYTES_PER_BLOCK;
    for b in 0..n {
        let s = &src[b * BYTES_PER_BLOCK..(b + 1) * BYTES_PER_BLOCK];
        let d = &mut dst[b * BLOCK..(b + 1) * BLOCK];
        dequantize_block(s, d);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pack_block(scale: f32, qs: &[u8; BLOCK]) -> Vec<u8> {
        let bits = crate::half::f32_to_f16_bits(scale);
        let mut out = Vec::with_capacity(BYTES_PER_BLOCK);
        out.extend_from_slice(&bits.to_le_bytes());
        for i in 0..BLOCK / 2 {
            let lo = qs[i] & 0x0f;
            let hi = (qs[i + BLOCK / 2] & 0x0f) << 4;
            out.push(lo | hi);
        }
        out
    }

    #[test]
    fn center_value_is_zero() {
        let qs = [8u8; BLOCK]; // 8 - 8 = 0
        let src = pack_block(1.0, &qs);
        let mut dst = [0.0f32; BLOCK];
        dequantize_block(&src, &mut dst);
        for d in &dst {
            assert!(d.abs() < 1e-6);
        }
    }

    #[test]
    fn endpoints() {
        // q=0 → -8, q=15 → +7, scaled by 0.5.
        let mut qs = [8u8; BLOCK];
        qs[0] = 0; // → -8 * 0.5 = -4
        qs[1] = 15; // → 7 * 0.5 = 3.5
        let src = pack_block(0.5, &qs);
        let mut dst = [0.0f32; BLOCK];
        dequantize_block(&src, &mut dst);
        assert!((dst[0] - -4.0).abs() < 1e-6);
        assert!((dst[1] - 3.5).abs() < 1e-6);
    }

    #[test]
    fn high_nibble_routes_to_upper_half() {
        let mut qs = [8u8; BLOCK];
        qs[16] = 15; // upper half element 0
        let src = pack_block(1.0, &qs);
        let mut dst = [0.0f32; BLOCK];
        dequantize_block(&src, &mut dst);
        assert!((dst[16] - 7.0).abs() < 1e-6);
    }
}
