//! SwiGLU activation.
//!
//! Math spec (Qwen memo):
//!
//! ```text
//!   SiLU(z)    = z / (1 + e^{-z})
//!   SwiGLU(x)  = SiLU(x · W_gate) ⊙ (x · W_up)         [intermediate-dim]
//!   FFN(x)     = SwiGLU(x) · W_down                     [back to hidden-dim]
//! ```
//!
//! In a transformer FFN block: `x` is hidden-dim; the kernel here takes
//! the already-computed `gate = x·W_gate` and `up = x·W_up` (both at
//! intermediate-dim) and produces `SiLU(gate) ⊙ up` at intermediate-dim.
//! The final `W_down` projection back to hidden-dim is a matmul, handled
//! by the caller.

/// SiLU(z) = z · sigmoid(z), pointwise. Returns a fresh `Vec` so it can be
/// fed straight into the elementwise multiply. Caller can recycle buffers
/// for hot paths via the in-place variant when available.
pub fn silu_f32(z: f32) -> f32 {
    z / (1.0 + (-z).exp())
}

/// `out[i] = SiLU(gate[i]) · up[i]`. All three buffers must be the same length
/// (the intermediate dim of the FFN). `out` may alias `gate` or `up` —
/// dependence is element-wise, so in-place writes are safe.
pub fn swiglu_f32(gate: &[f32], up: &[f32], out: &mut [f32]) {
    assert_eq!(gate.len(), up.len(), "gate and up must have the same dim");
    assert_eq!(gate.len(), out.len(), "out must match gate dim");
    for i in 0..gate.len() {
        out[i] = silu_f32(gate[i]) * up[i];
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn silu_at_zero_is_zero() {
        assert!(silu_f32(0.0).abs() < 1e-7);
    }

    #[test]
    fn silu_at_one_is_known() {
        // SiLU(1) = 1 / (1 + e^-1) ≈ 0.7310586
        let v = silu_f32(1.0);
        assert!((v - 0.7310586_f32).abs() < 1e-4, "v={v}");
    }

    #[test]
    fn silu_negative_is_close_to_zero_far_left() {
        // SiLU saturates to 0 as z -> -infinity.
        assert!(silu_f32(-10.0).abs() < 1e-3);
    }

    #[test]
    fn swiglu_matches_pointwise_formula() {
        let gate = [1.0_f32, 2.0, -1.0];
        let up = [0.5_f32, 1.0, 2.0];
        let mut out = [0.0_f32; 3];
        swiglu_f32(&gate, &up, &mut out);
        for i in 0..3 {
            let expected = silu_f32(gate[i]) * up[i];
            assert!((out[i] - expected).abs() < 1e-6);
        }
    }
}
