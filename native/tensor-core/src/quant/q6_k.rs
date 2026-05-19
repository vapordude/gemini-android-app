//! Q6_K dequantization. 256-value super-blocks; each block carries
//! 6-bit signed quants (low nibble in `ql`, high 2 bits in `qh`),
//! per-16-element int8 scales, and one f16 super-scale.
//!
//! Layout per super-block (210 bytes):
//!   ql[128]      lower 4 bits of each quant
//!   qh[64]       upper 2 bits of each quant, packed 4-per-byte
//!   scales[16]   int8 sub-block scales
//!   d (f16)      super-block scale
//!
//! Reconstruction (matches `dequantize_row_q6_K` in `ggml-quants.c`):
//!   q = ((ql[..] & 0xF) | ((qh[..] >> shift) & 0x3) << 4) - 32     // 6-bit signed
//!   out = d * scales[is] * q
//!
//! Common in Q4_K_M mixed quants — `output.weight` / `token_embd.weight`
//! often arrive Q6_K even when the rest of the file is Q4_K. Dequant
//! to F32 once at load time; matvec then dispatches the F32 kernel.

use crate::half::f16_to_f32;

pub const SUPER_BLOCK: usize = 256;
pub const BYTES_PER_SUPER_BLOCK: usize = 128 + 64 + 16 + 2;

/// Dequantize a contiguous slice of Q6_K super-blocks into `out`.
/// `src.len()` must be a multiple of [`BYTES_PER_SUPER_BLOCK`] and
/// `out.len()` must be a multiple of [`SUPER_BLOCK`] equal to the
/// number of super-blocks in `src`.
pub fn dequantize(src: &[u8], out: &mut [f32]) {
    debug_assert_eq!(src.len() % BYTES_PER_SUPER_BLOCK, 0);
    debug_assert_eq!(out.len() % SUPER_BLOCK, 0);
    debug_assert_eq!(src.len() / BYTES_PER_SUPER_BLOCK, out.len() / SUPER_BLOCK);

    let nb = src.len() / BYTES_PER_SUPER_BLOCK;
    for b in 0..nb {
        let src_block = &src[b * BYTES_PER_SUPER_BLOCK..(b + 1) * BYTES_PER_SUPER_BLOCK];
        let out_block = &mut out[b * SUPER_BLOCK..(b + 1) * SUPER_BLOCK];

        // Layout within a super-block.
        let ql = &src_block[0..128];
        let qh = &src_block[128..192];
        let scales = &src_block[192..208];
        let d_bits = u16::from_le_bytes([src_block[208], src_block[209]]);
        let d = f16_to_f32(d_bits);

        // Each super-block processes two 128-value halves; within each
        // half we read 32 `l`-positions and write 4 output positions
        // (l, l+32, l+64, l+96) per `l`.
        for half in 0..2 {
            let ql_off = half * 64;
            let qh_off = half * 32;
            let sc_off = half * 8;
            let out_off = half * 128;

            for l in 0..32 {
                let is = l / 16;
                let qh_byte = qh[qh_off + l] as i32;

                let ql_lo_a = ql[ql_off + l] as i32;
                let ql_lo_b = ql[ql_off + l + 32] as i32;

                let q1 = ((ql_lo_a & 0xF) | ((qh_byte & 0x3) << 4)) - 32;
                let q2 = ((ql_lo_b & 0xF) | (((qh_byte >> 2) & 0x3) << 4)) - 32;
                let q3 = ((ql_lo_a >> 4) | (((qh_byte >> 4) & 0x3) << 4)) - 32;
                let q4 = ((ql_lo_b >> 4) | (((qh_byte >> 6) & 0x3) << 4)) - 32;

                let s1 = scales[sc_off + is] as i8 as f32;
                let s2 = scales[sc_off + is + 2] as i8 as f32;
                let s3 = scales[sc_off + is + 4] as i8 as f32;
                let s4 = scales[sc_off + is + 6] as i8 as f32;

                out_block[out_off + l] = d * s1 * (q1 as f32);
                out_block[out_off + l + 32] = d * s2 * (q2 as f32);
                out_block[out_off + l + 64] = d * s3 * (q3 as f32);
                out_block[out_off + l + 96] = d * s4 * (q4 as f32);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_block_dequantizes_to_zero() {
        // 210-byte block of all zeros: d = 0, scales = 0, quants = 0.
        // The 6-bit signed reconstruction `(0 | 0) - 32 = -32` is then
        // multiplied by `d * scales = 0`, so the output is exactly zero.
        let src = vec![0u8; BYTES_PER_SUPER_BLOCK];
        let mut out = vec![0.0f32; SUPER_BLOCK];
        dequantize(&src, &mut out);
        assert!(out.iter().all(|&v| v == 0.0));
    }

    #[test]
    fn block_size_constants() {
        assert_eq!(BYTES_PER_SUPER_BLOCK, 210);
        assert_eq!(SUPER_BLOCK, 256);
    }
}
