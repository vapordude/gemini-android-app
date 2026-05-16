//! Rotary Positional Embeddings (RoPE).
//!
//! Math spec (Qwen memo):
//!
//! ```text
//! For position m and dim pair (2i, 2i+1):
//!
//!   ⎡ q_m^{(2i)}    ⎤   ⎡ cos(m·θ_i)  -sin(m·θ_i) ⎤ ⎡ q^{(2i)}    ⎤
//!   ⎢               ⎥ = ⎢                          ⎥ ⎢             ⎥
//!   ⎣ q_m^{(2i+1)}  ⎦   ⎣ sin(m·θ_i)   cos(m·θ_i) ⎦ ⎣ q^{(2i+1)}  ⎦
//!
//!   θ_i = base^(-2i / head_dim),  base = 10000 by default
//! ```
//!
//! Applied to Q and K only. Per-head: each head's `head_dim` values are
//! rotated independently with the same `(cos, sin)` table.

/// Precompute `cos(m·θ_i)` and `sin(m·θ_i)` for `m ∈ 0..max_pos` and
/// `i ∈ 0..head_dim/2`. Layout: `[max_pos, head_dim/2]` row-major.
/// Two arrays — cos, sin.
pub fn rope_freqs_f32(max_pos: usize, head_dim: usize, base: f32) -> (Vec<f32>, Vec<f32>) {
    assert!(head_dim.is_multiple_of(2), "head_dim must be even");
    let half = head_dim / 2;
    let mut cos = vec![0.0_f32; max_pos * half];
    let mut sin = vec![0.0_f32; max_pos * half];
    for i in 0..half {
        // θ_i = base^(-2i / head_dim)
        let theta = (base as f64).powf(-2.0 * (i as f64) / (head_dim as f64));
        for m in 0..max_pos {
            let angle = (m as f64) * theta;
            cos[m * half + i] = angle.cos() as f32;
            sin[m * half + i] = angle.sin() as f32;
        }
    }
    (cos, sin)
}

/// Apply RoPE in-place to `q` (or `k`). Shape: `[seq_len, num_heads, head_dim]`,
/// laid out row-major. `cos`/`sin` come from [`rope_freqs_f32`] and have
/// shape `[max_pos, head_dim/2]` — we only read rows `0..seq_len`.
pub fn rope_apply_f32(
    q: &mut [f32],
    seq_len: usize,
    num_heads: usize,
    head_dim: usize,
    cos: &[f32],
    sin: &[f32],
    start_pos: usize,
) {
    assert_eq!(head_dim % 2, 0);
    assert_eq!(q.len(), seq_len * num_heads * head_dim);
    let half = head_dim / 2;
    for s in 0..seq_len {
        let m = start_pos + s;
        let cos_row = &cos[m * half..(m + 1) * half];
        let sin_row = &sin[m * half..(m + 1) * half];
        for h in 0..num_heads {
            let off = (s * num_heads + h) * head_dim;
            for i in 0..half {
                // Pair (q_{2i}, q_{2i+1}) at offset (off + 2i, off + 2i + 1).
                let q0 = q[off + 2 * i];
                let q1 = q[off + 2 * i + 1];
                let c = cos_row[i];
                let s_v = sin_row[i];
                q[off + 2 * i]     = q0 * c - q1 * s_v;
                q[off + 2 * i + 1] = q0 * s_v + q1 * c;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rope_at_position_zero_is_identity() {
        let head_dim = 8;
        let (cos, sin) = rope_freqs_f32(4, head_dim, 10000.0);
        // At m=0, cos=1, sin=0 → rotation is the identity.
        for i in 0..head_dim / 2 {
            assert!((cos[i] - 1.0).abs() < 1e-6);
            assert!(sin[i].abs() < 1e-6);
        }
        let mut q = vec![1.0_f32, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0];
        let orig = q.clone();
        rope_apply_f32(&mut q, 1, 1, head_dim, &cos, &sin, 0);
        for i in 0..head_dim {
            assert!((q[i] - orig[i]).abs() < 1e-5);
        }
    }

    #[test]
    fn rope_preserves_norm() {
        // Rotation matrices are orthogonal — they preserve ‖q‖.
        let head_dim = 16;
        let (cos, sin) = rope_freqs_f32(8, head_dim, 10000.0);
        let mut q: Vec<f32> = (0..head_dim).map(|i| ((i as f32) * 0.3).sin()).collect();
        let norm_before: f32 = q.iter().map(|v| v * v).sum::<f32>().sqrt();
        rope_apply_f32(&mut q, 1, 1, head_dim, &cos, &sin, 5);
        let norm_after: f32 = q.iter().map(|v| v * v).sum::<f32>().sqrt();
        assert!((norm_before - norm_after).abs() < 1e-5,
            "norm changed: before={norm_before} after={norm_after}");
    }
}
