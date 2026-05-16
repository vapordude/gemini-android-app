//! Rotary Position Embedding. Pair-rotation form: each pair of consecutive
//! values (x[2i], x[2i+1]) is rotated by angle `pos * theta_i` where
//! `theta_i = 1 / base^(2i / head_dim)`.

/// Apply RoPE in-place to a single head vector at position `pos`.
pub fn rope_inplace_f32(x: &mut [f32], pos: usize, base: f32) {
    let dim = x.len();
    assert!(dim % 2 == 0, "rope head_dim must be even");
    let half = dim / 2;
    for i in 0..half {
        let theta = (pos as f32) * base.powf(-2.0 * i as f32 / dim as f32);
        let (sin, cos) = theta.sin_cos();
        let a = x[2 * i];
        let b = x[2 * i + 1];
        x[2 * i] = a * cos - b * sin;
        x[2 * i + 1] = a * sin + b * cos;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rope_pos_zero_is_identity() {
        let mut x = vec![1.0, 2.0, 3.0, 4.0];
        rope_inplace_f32(&mut x, 0, 10000.0);
        assert!((x[0] - 1.0).abs() < 1e-6);
        assert!((x[1] - 2.0).abs() < 1e-6);
        assert!((x[2] - 3.0).abs() < 1e-6);
        assert!((x[3] - 4.0).abs() < 1e-6);
    }

    #[test]
    fn rope_preserves_pair_norm() {
        let mut x = vec![0.7f32, -0.3, 0.4, 0.9];
        let n_before: Vec<f32> = x
            .chunks(2)
            .map(|p| (p[0] * p[0] + p[1] * p[1]).sqrt())
            .collect();
        rope_inplace_f32(&mut x, 5, 10000.0);
        let n_after: Vec<f32> = x
            .chunks(2)
            .map(|p| (p[0] * p[0] + p[1] * p[1]).sqrt())
            .collect();
        for (a, b) in n_before.iter().zip(n_after.iter()) {
            assert!((a - b).abs() < 1e-5, "pair norm changed: {a} vs {b}");
        }
    }
}
