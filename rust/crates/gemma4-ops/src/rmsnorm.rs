//! Root-Mean-Square Layer Normalization.
//!
//! Math spec (Qwen memo):
//!
//! ```text
//! RMSNorm:  x̄_i = x_i / sqrt( (1/D) · Σ_j x_j²  +  ε )  ·  g_i
//! ```
//!
//! Gemma 4 uses RMSNorm 4× per decoder block (pre-attn, post-attn, pre-FFN,
//! post-FFN — the "double-norm" pattern). The math here is the standard
//! formula; per-norm gain `g` is read from the weight file.

/// In-place is fine if `y == x` aliased — but the Rust signature keeps them
/// separate so the caller can choose. Both must be length `D`.
pub fn rmsnorm_f32(x: &[f32], g: &[f32], eps: f32, y: &mut [f32]) {
    assert_eq!(x.len(), g.len(), "x and g must have the same dim");
    assert_eq!(y.len(), x.len(), "y must match x dim");
    let d = x.len();
    if d == 0 { return; }

    // Σ x_j²  in F64 to keep the sum accurate for large D.
    let mut sum_sq: f64 = 0.0;
    for &v in x { sum_sq += (v as f64) * (v as f64); }
    let mean_sq = sum_sq / (d as f64);
    let scale = 1.0_f64 / (mean_sq + eps as f64).sqrt();
    let scale_f32 = scale as f32;

    for i in 0..d {
        y[i] = x[i] * scale_f32 * g[i];
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rmsnorm_unit_gain_preserves_unit_input() {
        // For x = [1,1,1,1], rms = 1, so output equals g.
        let x = [1.0_f32; 4];
        let g = [2.0_f32, 3.0, 4.0, 5.0];
        let mut y = [0.0_f32; 4];
        rmsnorm_f32(&x, &g, 1e-6, &mut y);
        for i in 0..4 {
            assert!((y[i] - g[i]).abs() < 1e-5, "y[{i}]={} g[{i}]={}", y[i], g[i]);
        }
    }

    #[test]
    fn rmsnorm_scales_correctly() {
        // For x = [2,2,2,2], rms = 2, so x/rms = [1,1,1,1], so y = g.
        let x = [2.0_f32; 4];
        let g = [1.0_f32; 4];
        let mut y = [0.0_f32; 4];
        rmsnorm_f32(&x, &g, 1e-6, &mut y);
        for v in y { assert!((v - 1.0).abs() < 1e-5); }
    }

    #[test]
    fn rmsnorm_matches_explicit_formula() {
        // x = [1, 2, 3, 4], g = [1; 4], eps = 0
        // sum_sq = 1+4+9+16 = 30
        // rms = sqrt(30/4) = sqrt(7.5) ≈ 2.7386
        // y_i = x_i / rms
        let x = [1.0_f32, 2.0, 3.0, 4.0];
        let g = [1.0_f32; 4];
        let mut y = [0.0_f32; 4];
        rmsnorm_f32(&x, &g, 0.0, &mut y);
        let rms = (7.5_f64).sqrt() as f32;
        for i in 0..4 {
            let expected = x[i] / rms;
            assert!((y[i] - expected).abs() < 1e-5, "y[{i}]={} expected={}", y[i], expected);
        }
    }
}
