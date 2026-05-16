//! Q4_K_M: k-quant 4-bit. Super-block of 256 elements grouped into 8
//! sub-blocks of 32. Each super-block:
//!   2 bytes — f16 d (super-scale for scales)
//!   2 bytes — f16 dmin (super-scale for mins)
//!  12 bytes — packed 6-bit scales + 6-bit mins for the 8 sub-blocks
//! 128 bytes — packed 4-bit weights (256 nibbles)
//!
//! Per sub-block `s` with weight nibble `q`:
//!   value = d * scale_s * q  -  dmin * min_s
//!
//! The 6-bit (scale,min) packing layout is the one llama.cpp ships in
//! `get_scale_min_k4`. Sub-blocks 0..4 store scale in low 6 bits of
//! bytes[0..4] and min in low 6 bits of bytes[4..8]. Sub-blocks 4..8
//! share the top 2 bits of bytes[0..8] with bytes[8..12]:
//!
//!   s = bytes[j]   & 0x3F                            (j < 4)
//!   m = bytes[j+4] & 0x3F                            (j < 4)
//!   s = (bytes[j+4] & 0x0F) | ((bytes[j-4] >> 6) << 4)   (j >= 4)
//!   m = (bytes[j+4] >> 4 )  | ((bytes[j  ] >> 6) << 4)   (j >= 4)
//!
//! See `dequantize_row_q4_K` in llama.cpp / ggml-quants.c for the reference.

use crate::half::f16_to_f32;

pub const SUPER_BLOCK: usize = 256;
pub const SUB_BLOCKS: usize = 8;
pub const SUB_SIZE: usize = SUPER_BLOCK / SUB_BLOCKS; // 32
pub const BYTES_PER_SUPER_BLOCK: usize = 2 + 2 + 12 + 128;

fn scale_min(j: usize, q: &[u8]) -> (u8, u8) {
    if j < 4 {
        (q[j] & 0x3F, q[j + 4] & 0x3F)
    } else {
        let lo_scale = q[j + 4] & 0x0F;
        let hi_scale = (q[j - 4] >> 6) << 4;
        let lo_min = q[j + 4] >> 4;
        let hi_min = (q[j] >> 6) << 4;
        (lo_scale | hi_scale, lo_min | hi_min)
    }
}

pub fn dequantize_block(src: &[u8], dst: &mut [f32]) {
    debug_assert_eq!(src.len(), BYTES_PER_SUPER_BLOCK);
    debug_assert_eq!(dst.len(), SUPER_BLOCK);

    let d = f16_to_f32(u16::from_le_bytes([src[0], src[1]]));
    let dmin = f16_to_f32(u16::from_le_bytes([src[2], src[3]]));
    let scales = &src[4..16];
    let qs = &src[16..16 + 128];

    // Walk pairs (lo, lo+4). qs[pair*32 + i] holds the lo nibble for
    // sub-block `pair` at element i, and the hi nibble for sub-block
    // `pair+4` at element i.
    for pair in 0..4 {
        let lo_sub = pair;
        let hi_sub = pair + 4;
        let (lo_scale, lo_min) = scale_min(lo_sub, scales);
        let (hi_scale, hi_min) = scale_min(hi_sub, scales);
        let scale_lo = d * lo_scale as f32;
        let scale_hi = d * hi_scale as f32;
        let bias_lo = dmin * lo_min as f32;
        let bias_hi = dmin * hi_min as f32;
        let qs_off = pair * SUB_SIZE;
        let lo_dst_off = lo_sub * SUB_SIZE;
        let hi_dst_off = hi_sub * SUB_SIZE;
        for i in 0..SUB_SIZE {
            let b = qs[qs_off + i];
            let q_lo = (b & 0x0F) as f32;
            let q_hi = (b >> 4) as f32;
            dst[lo_dst_off + i] = scale_lo * q_lo - bias_lo;
            dst[hi_dst_off + i] = scale_hi * q_hi - bias_hi;
        }
    }
}

/// Dequantize a contiguous run of Q4_K super-blocks.
pub fn dequantize(src: &[u8], dst: &mut [f32]) {
    debug_assert_eq!(src.len() % BYTES_PER_SUPER_BLOCK, 0);
    debug_assert_eq!(dst.len() % SUPER_BLOCK, 0);
    debug_assert_eq!(src.len() / BYTES_PER_SUPER_BLOCK, dst.len() / SUPER_BLOCK);
    let n = src.len() / BYTES_PER_SUPER_BLOCK;
    for b in 0..n {
        let s = &src[b * BYTES_PER_SUPER_BLOCK..(b + 1) * BYTES_PER_SUPER_BLOCK];
        let d = &mut dst[b * SUPER_BLOCK..(b + 1) * SUPER_BLOCK];
        dequantize_block(s, d);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_super(d: f32, dmin: f32, sm: [(u8, u8); 8], nibbles: [u8; SUPER_BLOCK]) -> Vec<u8> {
        let mut out = Vec::with_capacity(BYTES_PER_SUPER_BLOCK);
        out.extend_from_slice(&crate::half::f32_to_f16_bits(d).to_le_bytes());
        out.extend_from_slice(&crate::half::f32_to_f16_bits(dmin).to_le_bytes());
        // Pack scales + mins (6 bits each) using llama.cpp's layout.
        let mut scales_buf = [0u8; 12];
        for j in 0..4 {
            scales_buf[j] = sm[j].0 & 0x3F;
            scales_buf[j + 4] = sm[j].1 & 0x3F;
        }
        for j in 4..8 {
            let (sc, mn) = sm[j];
            scales_buf[j + 4] |= sc & 0x0F;
            scales_buf[j + 4] |= (mn & 0x0F) << 4;
            scales_buf[j - 4] |= ((sc >> 4) & 0x03) << 6;
            scales_buf[j] |= ((mn >> 4) & 0x03) << 6;
        }
        out.extend_from_slice(&scales_buf);
        // Pack nibbles: qs[p*32 + i] = (sub[p+4][i] << 4) | (sub[p][i] & 0x0F).
        for p in 0..4 {
            for i in 0..SUB_SIZE {
                let lo = nibbles[p * SUB_SIZE + i] & 0x0F;
                let hi = nibbles[(p + 4) * SUB_SIZE + i] & 0x0F;
                out.push(lo | (hi << 4));
            }
        }
        out
    }

    #[test]
    fn block_size_matches_spec() {
        assert_eq!(BYTES_PER_SUPER_BLOCK, 144);
    }

    #[test]
    fn all_zero_nibbles_dequantize_to_neg_bias() {
        // d=1, dmin=1, scales=0, mins=1 → value = 1*0*0 - 1*1 = -1
        let sm = [(0u8, 1u8); 8];
        let nibbles = [0u8; SUPER_BLOCK];
        let src = make_super(1.0, 1.0, sm, nibbles);
        let mut dst = vec![0.0f32; SUPER_BLOCK];
        dequantize_block(&src, &mut dst);
        for v in &dst {
            assert!((v - -1.0).abs() < 1e-5, "got {v}");
        }
    }

    #[test]
    fn scales_and_nibbles_apply_per_subblock() {
        // Sub-block 0: scale=2, min=0, nibbles=5 → value = 1*2*5 - 1*0 = 10.
        // Sub-block 7: scale=3, min=2, nibbles=7 → value = 1*3*7 - 1*2 = 19.
        let mut sm = [(0u8, 0u8); 8];
        sm[0] = (2, 0);
        sm[7] = (3, 2);
        let mut nibbles = [0u8; SUPER_BLOCK];
        for i in 0..SUB_SIZE {
            nibbles[i] = 5;
            nibbles[7 * SUB_SIZE + i] = 7;
        }
        let src = make_super(1.0, 1.0, sm, nibbles);
        let mut dst = vec![0.0f32; SUPER_BLOCK];
        dequantize_block(&src, &mut dst);
        for i in 0..SUB_SIZE {
            assert!((dst[i] - 10.0).abs() < 1e-5);
            assert!((dst[7 * SUB_SIZE + i] - 19.0).abs() < 1e-5);
        }
    }

    #[test]
    fn round_trip_two_super_blocks() {
        let sm = [(1u8, 0u8); 8];
        let nibbles = [3u8; SUPER_BLOCK];
        let one = make_super(0.5, 0.0, sm, nibbles);
        let mut src = Vec::new();
        src.extend(&one);
        src.extend(&one);
        let mut dst = vec![0.0f32; SUPER_BLOCK * 2];
        dequantize(&src, &mut dst);
        // value = 0.5 * 1 * 3 - 0 * 0 = 1.5
        for v in &dst {
            assert!((v - 1.5).abs() < 1e-5);
        }
    }
}
