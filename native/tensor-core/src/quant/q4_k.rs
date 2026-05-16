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

/// Compute `y = x · Wᵀ` where `W` is a `[n, k]` Q4_K_M-packed matrix
/// laid out row-by-row (the GGUF Linear-weight convention) and `x` is
/// a `[k]` f32 activation vector. Output `y` has length `n`.
///
/// On aarch64 this dispatches to a NEON kernel; everything else uses
/// the scalar reference. `k` must be a multiple of 256.
pub fn matvec_q4_k_row_major(w: &[u8], n: usize, k: usize, x: &[f32], y: &mut [f32]) {
    #[cfg(target_arch = "aarch64")]
    {
        // SAFETY: aarch64 baseline ABI mandates NEON. Inputs are
        // validated by the scalar's assertions, which we re-run inside.
        unsafe { neon::matvec_q4_k_row_major_neon(w, n, k, x, y) };
        return;
    }
    #[allow(unreachable_code)]
    matvec_q4_k_row_major_scalar(w, n, k, x, y)
}

/// Reference scalar implementation. Public so on-aarch64 tests can
/// run NEON-vs-scalar parity. Same contract as
/// `matvec_q4_k_row_major`. Walks each output row's super-blocks
/// against the activation in place — no intermediate dequant to f32.
/// For a 256 element sub-block we do
///
/// ```text
///   acc += (d * scale_s) * Σ(q_i · x_i)  -  (dmin * min_s) · Σ x_i
/// ```
///
/// so the inner loop is one mul-add per nibble. Activation reads are
/// shared across all output rows for a given column range, which is
/// what makes this much faster than a dequant+matmul split.
///
/// `k` must be a multiple of 256 (the super-block size).
pub fn matvec_q4_k_row_major_scalar(w: &[u8], n: usize, k: usize, x: &[f32], y: &mut [f32]) {
    assert_eq!(x.len(), k, "activation length mismatch");
    assert_eq!(y.len(), n, "output length mismatch");
    assert_eq!(k % SUPER_BLOCK, 0, "k must be a multiple of {SUPER_BLOCK}");
    let supers_per_row = k / SUPER_BLOCK;
    let row_bytes = supers_per_row * BYTES_PER_SUPER_BLOCK;
    assert_eq!(w.len(), n * row_bytes, "weight buffer size mismatch");

    // Precompute per-sub-block sums of x. The sub-block boundary in
    // x doesn't move per output row, so we pay this once.
    //
    // Layout: `x_sub_sums[block_idx * SUB_BLOCKS + sub_idx]` is the
    // sum of x over its 32 elements.
    let n_sub_blocks = supers_per_row * SUB_BLOCKS;
    let mut x_sub_sums = vec![0.0f32; n_sub_blocks];
    for block in 0..supers_per_row {
        for sub in 0..SUB_BLOCKS {
            let base = block * SUPER_BLOCK + sub * SUB_SIZE;
            let mut s = 0.0f32;
            for i in 0..SUB_SIZE {
                s += x[base + i];
            }
            x_sub_sums[block * SUB_BLOCKS + sub] = s;
        }
    }

    for (row, y_slot) in y.iter_mut().enumerate().take(n) {
        let row_start = row * row_bytes;
        let row_w = &w[row_start..row_start + row_bytes];
        let mut acc = 0.0f32;
        for block in 0..supers_per_row {
            let src = &row_w[block * BYTES_PER_SUPER_BLOCK..(block + 1) * BYTES_PER_SUPER_BLOCK];
            let d = f16_to_f32(u16::from_le_bytes([src[0], src[1]]));
            let dmin = f16_to_f32(u16::from_le_bytes([src[2], src[3]]));
            let scales = &src[4..16];
            let qs = &src[16..16 + 128];
            let x_block_base = block * SUPER_BLOCK;
            for pair in 0..4 {
                let lo_sub = pair;
                let hi_sub = pair + 4;
                let (lo_scale, lo_min) = scale_min(lo_sub, scales);
                let (hi_scale, hi_min) = scale_min(hi_sub, scales);
                let scale_lo = d * lo_scale as f32;
                let scale_hi = d * hi_scale as f32;
                let bias_lo = dmin * lo_min as f32;
                let bias_hi = dmin * hi_min as f32;
                let lo_x_base = x_block_base + lo_sub * SUB_SIZE;
                let hi_x_base = x_block_base + hi_sub * SUB_SIZE;
                let qs_off = pair * SUB_SIZE;
                let mut sum_qx_lo = 0.0f32;
                let mut sum_qx_hi = 0.0f32;
                for i in 0..SUB_SIZE {
                    let b = qs[qs_off + i];
                    let q_lo = (b & 0x0F) as f32;
                    let q_hi = (b >> 4) as f32;
                    sum_qx_lo += q_lo * x[lo_x_base + i];
                    sum_qx_hi += q_hi * x[hi_x_base + i];
                }
                let sum_x_lo = x_sub_sums[block * SUB_BLOCKS + lo_sub];
                let sum_x_hi = x_sub_sums[block * SUB_BLOCKS + hi_sub];
                acc += scale_lo * sum_qx_lo - bias_lo * sum_x_lo;
                acc += scale_hi * sum_qx_hi - bias_hi * sum_x_hi;
            }
        }
        *y_slot = acc;
    }
}

/// NEON-accelerated Q4_K_M matvec. aarch64 baseline ABI mandates NEON,
/// so we skip runtime feature detection. The kernel matches the scalar
/// reference bit-for-bit up to f32-fma rounding; the test below
/// asserts parity within 1e-4 over a random 4096-element row.
#[cfg(target_arch = "aarch64")]
mod neon {
    use super::{f16_to_f32, scale_min, BYTES_PER_SUPER_BLOCK, SUB_BLOCKS, SUB_SIZE, SUPER_BLOCK};
    use core::arch::aarch64::*;

    #[target_feature(enable = "neon")]
    pub unsafe fn matvec_q4_k_row_major_neon(
        w: &[u8],
        n: usize,
        k: usize,
        x: &[f32],
        y: &mut [f32],
    ) {
        assert_eq!(x.len(), k, "activation length mismatch");
        assert_eq!(y.len(), n, "output length mismatch");
        assert_eq!(k % SUPER_BLOCK, 0, "k must be a multiple of {SUPER_BLOCK}");
        let supers_per_row = k / SUPER_BLOCK;
        let row_bytes = supers_per_row * BYTES_PER_SUPER_BLOCK;
        assert_eq!(w.len(), n * row_bytes, "weight buffer size mismatch");

        // SAFETY: all intrinsics below operate on pointers we just
        // bounds-checked through slice indexing. NEON is ABI-baseline
        // on aarch64. We compute per-sub-block sums up front (shared
        // across all output rows) then process each row's super-blocks
        // 32 nibbles at a time.
        unsafe {
            // Same precompute as scalar — sums of x per 32-element
            // sub-block. We use a Vec rather than the input slice
            // because the scalar reference does it once per call and
            // we want apples-to-apples.
            let n_sub_blocks = supers_per_row * SUB_BLOCKS;
            let mut x_sub_sums = vec![0.0f32; n_sub_blocks];
            for block in 0..supers_per_row {
                for sub in 0..SUB_BLOCKS {
                    let base = block * SUPER_BLOCK + sub * SUB_SIZE;
                    let mut acc = vdupq_n_f32(0.0);
                    // 32 elements = 8 lanes of 4 floats.
                    for chunk in 0..8 {
                        let v = vld1q_f32(x.as_ptr().add(base + chunk * 4));
                        acc = vaddq_f32(acc, v);
                    }
                    x_sub_sums[block * SUB_BLOCKS + sub] = vaddvq_f32(acc);
                }
            }

            let mask_lo = vdupq_n_u8(0x0F);

            for (row, y_slot) in y.iter_mut().enumerate().take(n) {
                let row_start = row * row_bytes;
                let row_w = &w[row_start..row_start + row_bytes];
                let mut acc_row = 0.0f32;
                for block in 0..supers_per_row {
                    let src =
                        &row_w[block * BYTES_PER_SUPER_BLOCK..(block + 1) * BYTES_PER_SUPER_BLOCK];
                    let d = f16_to_f32(u16::from_le_bytes([src[0], src[1]]));
                    let dmin = f16_to_f32(u16::from_le_bytes([src[2], src[3]]));
                    let scales = &src[4..16];
                    let qs = src.as_ptr().add(16); // 128 bytes of nibble pairs
                    let x_block_base = block * SUPER_BLOCK;

                    for pair in 0..4 {
                        let lo_sub = pair;
                        let hi_sub = pair + 4;
                        let (lo_scale_b, lo_min_b) = scale_min(lo_sub, scales);
                        let (hi_scale_b, hi_min_b) = scale_min(hi_sub, scales);
                        let scale_lo = d * lo_scale_b as f32;
                        let scale_hi = d * hi_scale_b as f32;
                        let bias_lo = dmin * lo_min_b as f32;
                        let bias_hi = dmin * hi_min_b as f32;

                        let lo_x_ptr = x.as_ptr().add(x_block_base + lo_sub * SUB_SIZE);
                        let hi_x_ptr = x.as_ptr().add(x_block_base + hi_sub * SUB_SIZE);
                        let qs_pair_ptr = qs.add(pair * SUB_SIZE); // 32 bytes

                        let mut acc_lo = vdupq_n_f32(0.0);
                        let mut acc_hi = vdupq_n_f32(0.0);

                        // Two 16-byte chunks per pair (covers 32 nibbles each side).
                        for chunk in 0..2 {
                            let q_bytes = vld1q_u8(qs_pair_ptr.add(chunk * 16));
                            let lo_u8 = vandq_u8(q_bytes, mask_lo);
                            let hi_u8 = vshrq_n_u8(q_bytes, 4);

                            // Widen each u8x16 into 4 lanes of f32x4.
                            let lo_u16_lo = vmovl_u8(vget_low_u8(lo_u8));
                            let lo_u16_hi = vmovl_high_u8(lo_u8);
                            let hi_u16_lo = vmovl_u8(vget_low_u8(hi_u8));
                            let hi_u16_hi = vmovl_high_u8(hi_u8);

                            let lo_f32_a = vcvtq_f32_u32(vmovl_u16(vget_low_u16(lo_u16_lo)));
                            let lo_f32_b = vcvtq_f32_u32(vmovl_high_u16(lo_u16_lo));
                            let lo_f32_c = vcvtq_f32_u32(vmovl_u16(vget_low_u16(lo_u16_hi)));
                            let lo_f32_d = vcvtq_f32_u32(vmovl_high_u16(lo_u16_hi));

                            let hi_f32_a = vcvtq_f32_u32(vmovl_u16(vget_low_u16(hi_u16_lo)));
                            let hi_f32_b = vcvtq_f32_u32(vmovl_high_u16(hi_u16_lo));
                            let hi_f32_c = vcvtq_f32_u32(vmovl_u16(vget_low_u16(hi_u16_hi)));
                            let hi_f32_d = vcvtq_f32_u32(vmovl_high_u16(hi_u16_hi));

                            let xl0 = vld1q_f32(lo_x_ptr.add(chunk * 16));
                            let xl1 = vld1q_f32(lo_x_ptr.add(chunk * 16 + 4));
                            let xl2 = vld1q_f32(lo_x_ptr.add(chunk * 16 + 8));
                            let xl3 = vld1q_f32(lo_x_ptr.add(chunk * 16 + 12));
                            let xh0 = vld1q_f32(hi_x_ptr.add(chunk * 16));
                            let xh1 = vld1q_f32(hi_x_ptr.add(chunk * 16 + 4));
                            let xh2 = vld1q_f32(hi_x_ptr.add(chunk * 16 + 8));
                            let xh3 = vld1q_f32(hi_x_ptr.add(chunk * 16 + 12));

                            acc_lo = vfmaq_f32(acc_lo, lo_f32_a, xl0);
                            acc_lo = vfmaq_f32(acc_lo, lo_f32_b, xl1);
                            acc_lo = vfmaq_f32(acc_lo, lo_f32_c, xl2);
                            acc_lo = vfmaq_f32(acc_lo, lo_f32_d, xl3);

                            acc_hi = vfmaq_f32(acc_hi, hi_f32_a, xh0);
                            acc_hi = vfmaq_f32(acc_hi, hi_f32_b, xh1);
                            acc_hi = vfmaq_f32(acc_hi, hi_f32_c, xh2);
                            acc_hi = vfmaq_f32(acc_hi, hi_f32_d, xh3);
                        }

                        let sum_qx_lo = vaddvq_f32(acc_lo);
                        let sum_qx_hi = vaddvq_f32(acc_hi);
                        let sum_x_lo = x_sub_sums[block * SUB_BLOCKS + lo_sub];
                        let sum_x_hi = x_sub_sums[block * SUB_BLOCKS + hi_sub];
                        acc_row += scale_lo * sum_qx_lo - bias_lo * sum_x_lo;
                        acc_row += scale_hi * sum_qx_hi - bias_hi * sum_x_hi;
                    }
                }
                *y_slot = acc_row;
            }
        } // unsafe
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

    /// matvec_q4_k_row_major must match the result of dequantizing every
    /// row to F32 and running a plain matmul. The activation here is a
    /// pseudo-random pattern so cancellation errors would show.
    #[test]
    #[allow(clippy::needless_range_loop)] // explicit index arithmetic is clearer for the fixture
    fn matvec_matches_dequant_then_matmul() {
        // Build a 3-row × (2 super-block = 512) matrix with varied scales.
        let n = 3;
        let supers_per_row = 2;
        let k = supers_per_row * SUPER_BLOCK;
        let mut weight_bytes = Vec::with_capacity(n * supers_per_row * BYTES_PER_SUPER_BLOCK);
        for row in 0..n {
            for b in 0..supers_per_row {
                let mut sm = [(0u8, 0u8); 8];
                for j in 0..8 {
                    sm[j] = (
                        ((row * 7 + b * 3 + j) as u8) % 8 + 1,
                        ((j * 5 + b) as u8) % 4,
                    );
                }
                let mut nibbles = [0u8; SUPER_BLOCK];
                for i in 0..SUPER_BLOCK {
                    nibbles[i] = ((row * 31 + b * 17 + i * 3) as u8) & 0x0F;
                }
                let d = 0.25 + (b as f32) * 0.1;
                let dmin = 0.05 + (row as f32) * 0.02;
                weight_bytes.extend(make_super(d, dmin, sm, nibbles));
            }
        }
        // Dequantize the whole matrix for comparison.
        let mut weight_f32 = vec![0.0f32; n * k];
        for row in 0..n {
            let row_bytes_start = row * supers_per_row * BYTES_PER_SUPER_BLOCK;
            let row_bytes_end = (row + 1) * supers_per_row * BYTES_PER_SUPER_BLOCK;
            let row_dst_start = row * k;
            let row_dst_end = (row + 1) * k;
            dequantize(
                &weight_bytes[row_bytes_start..row_bytes_end],
                &mut weight_f32[row_dst_start..row_dst_end],
            );
        }
        // Activation: deterministic pseudo-random sequence.
        let x: Vec<f32> = (0..k).map(|i| ((i as f32) * 0.0137).sin()).collect();
        // Reference: y_ref[row] = sum_k weight_f32[row, k] * x[k].
        let mut y_ref = vec![0.0f32; n];
        for row in 0..n {
            let mut acc = 0.0f32;
            for col in 0..k {
                acc += weight_f32[row * k + col] * x[col];
            }
            y_ref[row] = acc;
        }
        // Kernel under test.
        let mut y = vec![0.0f32; n];
        matvec_q4_k_row_major(&weight_bytes, n, k, &x, &mut y);
        for row in 0..n {
            let err = (y[row] - y_ref[row]).abs();
            // Q4_K_M error bound: scale * 8 + dmin, scaled by sum |x| ≈ 0.6 here.
            // The kernel itself does the same arithmetic in a different order;
            // the tolerance is for f32 accumulation, not for quant error.
            assert!(
                err < 1e-3,
                "row {row}: matvec {} vs ref {} (delta {err})",
                y[row],
                y_ref[row],
            );
        }
    }

    /// On aarch64, assert the NEON kernel matches the scalar reference
    /// bit-for-bit (within f32-FMA rounding). This test compiles + runs
    /// only when the target is aarch64; on x86 CI it's silently absent.
    #[cfg(target_arch = "aarch64")]
    #[test]
    #[allow(clippy::needless_range_loop)] // explicit index arithmetic is clearer for the fixture
    fn neon_matches_scalar_on_random_weights() {
        // 5 rows × 4 super-blocks = 1024 columns. Large enough that
        // any per-block bug surfaces; small enough to debug.
        let n = 5;
        let supers_per_row = 4;
        let k = supers_per_row * SUPER_BLOCK;
        let mut weight_bytes = Vec::with_capacity(n * supers_per_row * BYTES_PER_SUPER_BLOCK);
        for row in 0..n {
            for b in 0..supers_per_row {
                let mut sm = [(0u8, 0u8); 8];
                for j in 0..8 {
                    sm[j] = (
                        ((row * 11 + b * 5 + j) as u8) % 12 + 1,
                        ((j * 3 + b) as u8) % 8,
                    );
                }
                let mut nibbles = [0u8; SUPER_BLOCK];
                for i in 0..SUPER_BLOCK {
                    nibbles[i] = ((row * 53 + b * 19 + i * 7) as u8) & 0x0F;
                }
                let d = 0.18 + (b as f32) * 0.07;
                let dmin = 0.04 + (row as f32) * 0.013;
                weight_bytes.extend(make_super(d, dmin, sm, nibbles));
            }
        }
        let x: Vec<f32> = (0..k).map(|i| ((i as f32) * 0.0091).cos() * 0.5).collect();

        let mut y_scalar = vec![0.0f32; n];
        matvec_q4_k_row_major_scalar(&weight_bytes, n, k, &x, &mut y_scalar);

        let mut y_neon = vec![0.0f32; n];
        matvec_q4_k_row_major(&weight_bytes, n, k, &x, &mut y_neon);

        for row in 0..n {
            let err = (y_neon[row] - y_scalar[row]).abs();
            // FMA path can reorder a few additions; tolerance covers
            // up to ~1024 ops × half-ULP-ish, generous.
            assert!(
                err < 1e-4,
                "row {row}: NEON {} vs scalar {} (delta {err})",
                y_neon[row],
                y_scalar[row],
            );
        }
    }
}
