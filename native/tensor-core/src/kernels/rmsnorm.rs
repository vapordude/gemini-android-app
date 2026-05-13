/// `out = x / sqrt(mean(x^2) + eps) * weight`.
pub fn rmsnorm_f32(x: &[f32], weight: &[f32], out: &mut [f32], eps: f32) {
    assert_eq!(x.len(), weight.len());
    assert_eq!(x.len(), out.len());
    let n = x.len() as f32;
    let mut ssq = 0.0f32;
    for &v in x {
        ssq += v * v;
    }
    let scale = 1.0f32 / (ssq / n + eps).sqrt();
    for i in 0..x.len() {
        out[i] = x[i] * scale * weight[i];
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unit_weight_normalizes_to_unit_rms() {
        let x = vec![1.0f32, 2.0, 3.0, 4.0];
        let w = vec![1.0f32; 4];
        let mut out = vec![0.0; 4];
        rmsnorm_f32(&x, &w, &mut out, 0.0);
        let rms = (out.iter().map(|v| v * v).sum::<f32>() / 4.0).sqrt();
        assert!((rms - 1.0).abs() < 1e-5);
    }

    #[test]
    fn weight_scales_output() {
        let x = vec![1.0f32; 4];
        let w = vec![2.0f32; 4];
        let mut out = vec![0.0; 4];
        rmsnorm_f32(&x, &w, &mut out, 0.0);
        for v in out {
            assert!((v - 2.0).abs() < 1e-5);
        }
    }
}
