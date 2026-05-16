//! Numerically stable softmax.
//!
//! Math spec (Qwen memo):
//!
//! ```text
//!   softmax(z_i) = exp(z_i - max(z)) / Σ_j exp(z_j - max(z))
//! ```
//!
//! Causal mask is applied by setting future positions to `-∞` before
//! softmax — handled by the caller; this kernel just runs the reduction.

/// Softmax in-place over the last axis of `z`. Length `n` is the size of that
/// last axis. Caller has already applied any mask (e.g. `-inf` for j > i).
/// `scale` multiplies each element before the max-subtract — used to fold
/// the `1/√d_k` attention scaling into the softmax.
pub fn softmax_inplace_f32(z: &mut [f32], scale: f32) {
    if z.is_empty() { return; }
    // Pass 1: find max(scale·z) for numerical stability.
    let mut m = f32::NEG_INFINITY;
    for &v in z.iter() {
        let sv = v * scale;
        if sv > m { m = sv; }
    }
    // If everything is -inf (e.g. fully-masked row in some edge cases),
    // return uniform 0 to avoid 0/0. This shouldn't happen in real attention.
    if !m.is_finite() {
        for v in z.iter_mut() { *v = 0.0; }
        return;
    }
    // Pass 2: exponentiate (in-place, into z) and accumulate the denominator.
    let mut sum: f64 = 0.0;
    for v in z.iter_mut() {
        let e = ((*v * scale - m) as f64).exp();
        *v = e as f32;
        sum += e;
    }
    // Pass 3: divide by the sum.
    let inv = (1.0_f64 / sum) as f32;
    for v in z.iter_mut() { *v *= inv; }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn softmax_normalizes_to_one() {
        let mut z = [1.0_f32, 2.0, 3.0, 4.0];
        softmax_inplace_f32(&mut z, 1.0);
        let sum: f32 = z.iter().sum();
        assert!((sum - 1.0).abs() < 1e-6, "sum={sum}");
        // Each element must be positive.
        for v in z { assert!(v > 0.0); }
    }

    #[test]
    fn softmax_is_invariant_under_constant_shift() {
        let mut a = [1.0_f32, 2.0, 3.0];
        let mut b = [101.0_f32, 102.0, 103.0];
        softmax_inplace_f32(&mut a, 1.0);
        softmax_inplace_f32(&mut b, 1.0);
        for i in 0..3 {
            assert!((a[i] - b[i]).abs() < 1e-6, "i={i} a={} b={}", a[i], b[i]);
        }
    }

    #[test]
    fn softmax_handles_negative_infinity_mask() {
        // -inf positions get probability 0; the rest sum to 1.
        let mut z = [1.0_f32, f32::NEG_INFINITY, 2.0];
        softmax_inplace_f32(&mut z, 1.0);
        assert!(z[1].abs() < 1e-6);
        assert!((z[0] + z[2] - 1.0).abs() < 1e-6);
    }
}
