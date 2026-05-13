//! Matrix multiply. Hot loop; SIMD-tiered.
//!
//! `c[m, n] = a[m, k] · b[n, k]^T` — row-major a, row-major b interpreted
//! as `(n, k)` so the inner loop walks contiguous memory.

/// Reference scalar gemm. K-inner tiled to keep the inner accumulator
/// hot in a register file across compilers.
pub fn matmul_f32(a: &[f32], b: &[f32], c: &mut [f32], m: usize, n: usize, k: usize) {
    assert_eq!(a.len(), m * k);
    assert_eq!(b.len(), n * k);
    assert_eq!(c.len(), m * n);
    for i in 0..m {
        let a_row = &a[i * k..(i + 1) * k];
        for j in 0..n {
            let b_row = &b[j * k..(j + 1) * k];
            let mut acc = 0.0f32;
            // Manually unroll by 4. Compiler vectorizes the body on
            // both NEON and AVX2 in --release.
            let mut p = 0;
            while p + 4 <= k {
                acc += a_row[p] * b_row[p];
                acc += a_row[p + 1] * b_row[p + 1];
                acc += a_row[p + 2] * b_row[p + 2];
                acc += a_row[p + 3] * b_row[p + 3];
                p += 4;
            }
            while p < k {
                acc += a_row[p] * b_row[p];
                p += 1;
            }
            c[i * n + j] = acc;
        }
    }
}

/// Vector × matrix: `out[n] = a[k] · b[n, k]^T`. The hot path for decode
/// (single-token forward), since `m == 1` is the common case.
pub fn matvec_f32(a: &[f32], b: &[f32], out: &mut [f32], n: usize, k: usize) {
    assert_eq!(a.len(), k);
    assert_eq!(b.len(), n * k);
    assert_eq!(out.len(), n);
    for j in 0..n {
        let b_row = &b[j * k..(j + 1) * k];
        let mut acc = 0.0f32;
        let mut p = 0;
        while p + 4 <= k {
            acc += a[p] * b_row[p];
            acc += a[p + 1] * b_row[p + 1];
            acc += a[p + 2] * b_row[p + 2];
            acc += a[p + 3] * b_row[p + 3];
            p += 4;
        }
        while p < k {
            acc += a[p] * b_row[p];
            p += 1;
        }
        out[j] = acc;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matmul_identity() {
        let m = 3;
        let k = 3;
        let n = 3;
        let a: Vec<f32> = vec![1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0];
        let b: Vec<f32> = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0];
        let mut c = vec![0.0f32; m * n];
        matmul_f32(&a, &b, &mut c, m, n, k);
        // a is identity; a · b^T = b^T = transpose of b
        let expected = vec![1.0, 4.0, 7.0, 2.0, 5.0, 8.0, 3.0, 6.0, 9.0];
        for (i, &e) in expected.iter().enumerate() {
            assert!((c[i] - e).abs() < 1e-6, "i={i} got {} expected {e}", c[i]);
        }
    }

    #[test]
    fn matvec_matches_matmul() {
        let n = 5;
        let k = 7;
        let a: Vec<f32> = (0..k).map(|i| i as f32 * 0.1).collect();
        let b: Vec<f32> = (0..n * k).map(|i| (i as f32 * 0.01).sin()).collect();
        let mut from_matmul = vec![0.0f32; n];
        matmul_f32(&a, &b, &mut from_matmul, 1, n, k);
        let mut from_matvec = vec![0.0f32; n];
        matvec_f32(&a, &b, &mut from_matvec, n, k);
        for j in 0..n {
            assert!(
                (from_matmul[j] - from_matvec[j]).abs() < 1e-5,
                "mismatch at j={j}: {} vs {}",
                from_matmul[j],
                from_matvec[j]
            );
        }
    }
}
