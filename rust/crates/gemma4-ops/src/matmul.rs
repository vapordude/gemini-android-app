//! Matrix multiplication primitives.
//!
//! Math spec (Qwen memo):
//!
//! ```text
//! Y = X · W + b      (no transpose; X is [M,K], W is [K,N], Y is [M,N])
//! Y_{ij} = Σ_{k=0..K-1} X_{ik} · W_{kj}  +  b_j
//! ```
//!
//! Implementation:
//!
//! - Three-deep cache-blocking loop (MC × KC × NC tiles).
//! - F32 accumulators inside the micro-kernel to prevent catastrophic
//!   cancellation when inputs are BF16/F16.
//! - Scalar reference path is the parity oracle. AArch64 NEON
//!   specialization will be added behind `#[cfg(target_arch="aarch64")]`
//!   in [`matmul_bf16_acc_f32`] once it ships.

use gemma4_core::dtype::bf16_to_f32;

/// Cache-block constants. 64×512×128 micro-kernel fits in L1 on a typical
/// big-core (X925) — 64KiB working set per tile pair.
const MC: usize = 64;
const KC: usize = 512;
const NC: usize = 128;

/// `Y = X · W + bias` for f32 inputs/output. `bias` may be empty (no add).
///
/// All buffers are row-major. Panics on shape mismatch — caller is the
/// model code which knows the exact dims at compile time.
#[allow(clippy::too_many_arguments)] // dims are intrinsic to GEMM
pub fn matmul_f32(
    x: &[f32], x_rows: usize, x_cols: usize,
    w: &[f32], w_rows: usize, w_cols: usize,
    bias: &[f32],
    y: &mut [f32],
) {
    assert_eq!(x_cols, w_rows, "X cols must match W rows");
    let m = x_rows;
    let k = x_cols;
    let n = w_cols;
    assert_eq!(x.len(), m * k, "X buffer size mismatch");
    assert_eq!(w.len(), k * n, "W buffer size mismatch");
    assert_eq!(y.len(), m * n, "Y buffer size mismatch");
    if !bias.is_empty() {
        assert_eq!(bias.len(), n, "bias must be empty or length N");
    }

    // Initialize Y with bias (or zero).
    if bias.is_empty() {
        for v in y.iter_mut() { *v = 0.0; }
    } else {
        for i in 0..m {
            let row = &mut y[i * n..(i + 1) * n];
            row.copy_from_slice(bias);
        }
    }

    // Three-level cache blocking.
    let mut ic = 0;
    while ic < m {
        let mc = MC.min(m - ic);
        let mut pc = 0;
        while pc < k {
            let kc = KC.min(k - pc);
            let mut jc = 0;
            while jc < n {
                let nc = NC.min(n - jc);
                micro_kernel_f32(
                    &x[ic * k..],
                    &w[pc * n..],
                    &mut y[ic * n..],
                    mc, kc, nc,
                    k, n, n,
                    pc, jc,
                );
                jc += NC;
            }
            pc += KC;
        }
        ic += MC;
    }
}

#[allow(clippy::too_many_arguments)] // tile dims are intrinsic to the micro-kernel
fn micro_kernel_f32(
    x_base: &[f32],
    w_base: &[f32],
    y_base: &mut [f32],
    mr: usize, kr: usize, nr: usize,
    x_stride: usize, w_stride: usize, y_stride: usize,
    pc_off: usize, jc_off: usize,
) {
    for i in 0..mr {
        let xi = &x_base[i * x_stride..];
        let yi = &mut y_base[i * y_stride..];
        for p in 0..kr {
            let a = xi[pc_off + p];
            let wp = &w_base[p * w_stride..];
            for j in 0..nr {
                yi[jc_off + j] += a * wp[jc_off + j];
            }
        }
    }
}

/// Same shape as [`matmul_f32`] but inputs are BF16 (stored as `u16`).
/// Accumulation is in F32 — Qwen memo: "accumulation in extended precision
/// to prevent catastrophic cancellation". Output is BF16.
#[allow(clippy::too_many_arguments)] // dims are intrinsic to GEMM
pub fn matmul_bf16_acc_f32(
    x: &[u16], x_rows: usize, x_cols: usize,
    w: &[u16], w_rows: usize, w_cols: usize,
    bias: &[u16],
    y: &mut [u16],
) {
    assert_eq!(x_cols, w_rows);
    let m = x_rows;
    let k = x_cols;
    let n = w_cols;
    assert_eq!(x.len(), m * k);
    assert_eq!(w.len(), k * n);
    assert_eq!(y.len(), m * n);
    if !bias.is_empty() { assert_eq!(bias.len(), n); }

    // Stage-1: dequantize on the fly into F32 accumulators. For correctness
    // first; NEON dot-product specialization comes later.
    for i in 0..m {
        for j in 0..n {
            let mut acc: f32 = if bias.is_empty() { 0.0 } else { bf16_to_f32(bias[j]) };
            for p in 0..k {
                acc += bf16_to_f32(x[i * k + p]) * bf16_to_f32(w[p * n + j]);
            }
            y[i * n + j] = gemma4_core::dtype::f32_to_bf16(acc);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Hand-computed 2×3 · 3×2 product (no bias).
    ///   X = [[1,2,3],[4,5,6]]
    ///   W = [[7,8],[9,10],[11,12]]
    ///   Y = [[58,64],[139,154]]
    #[test]
    fn matmul_f32_matches_hand_computed() {
        let x = [1.0_f32, 2.0, 3.0, 4.0, 5.0, 6.0];
        let w = [7.0_f32, 8.0, 9.0, 10.0, 11.0, 12.0];
        let mut y = [0.0_f32; 4];
        matmul_f32(&x, 2, 3, &w, 3, 2, &[], &mut y);
        assert_eq!(y, [58.0, 64.0, 139.0, 154.0]);
    }

    #[test]
    fn matmul_f32_applies_bias() {
        let x = [1.0_f32, 2.0, 3.0, 4.0];
        let w = [1.0_f32, 0.0, 0.0, 1.0];
        let bias = [10.0_f32, 20.0];
        let mut y = [0.0_f32; 4];
        matmul_f32(&x, 2, 2, &w, 2, 2, &bias, &mut y);
        assert_eq!(y, [11.0, 22.0, 13.0, 24.0]);
    }
}
