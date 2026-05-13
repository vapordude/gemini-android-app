/// `out = x / sqrt(mean(x^2) + eps) * weight`.
pub fn rmsnorm_f32(x: &[f32], weight: &[f32], out: &mut [f32], eps: f32) {
    debug_assert_eq!(x.len(), weight.len());
    debug_assert_eq!(x.len(), out.len());
    let n = x.len() as f32;
    let mut ssq = 0.0f32;
    for &v in x {
        ssq += v * v;
    }
    let scale = 1.0f32 / libm::sqrtf(ssq / n + eps);
    for i in 0..x.len() {
        out[i] = x[i] * scale * weight[i];
    }
}
