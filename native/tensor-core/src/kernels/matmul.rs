//! Matrix multiply. Hot loop; SIMD-tiered.

/// `c = a · b^T`. Row-major.
///
/// Reference scalar implementation. SIMD variants land alongside.
pub fn matmul_f32(a: &[f32], b: &[f32], c: &mut [f32], m: usize, n: usize, k: usize) {
    debug_assert_eq!(a.len(), m * k);
    debug_assert_eq!(b.len(), n * k);
    debug_assert_eq!(c.len(), m * n);
    for i in 0..m {
        for j in 0..n {
            let mut acc = 0.0f32;
            for p in 0..k {
                acc += a[i * k + p] * b[j * k + p];
            }
            c[i * n + j] = acc;
        }
    }
}
