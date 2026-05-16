/// SwiGLU activation: `out = silu(gate) * up`, where silu(x) = x * sigmoid(x).
pub fn swiglu_f32(gate: &[f32], up: &[f32], out: &mut [f32]) {
    assert_eq!(gate.len(), up.len());
    assert_eq!(gate.len(), out.len());
    for i in 0..gate.len() {
        let g = gate[i];
        let silu = g / (1.0 + (-g).exp());
        out[i] = silu * up[i];
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_gate_zeroes_output() {
        let g = vec![0.0f32; 4];
        let u = vec![1.0, 2.0, 3.0, 4.0];
        let mut o = vec![0.0; 4];
        swiglu_f32(&g, &u, &mut o);
        for v in o {
            assert!(v.abs() < 1e-6);
        }
    }

    #[test]
    fn large_positive_gate_is_near_identity() {
        let g = vec![100.0f32; 4];
        let u = vec![1.0, -2.0, 3.0, -4.0];
        let mut o = vec![0.0; 4];
        swiglu_f32(&g, &u, &mut o);
        for i in 0..4 {
            // silu(100) ≈ 100, output ≈ 100 * up
            assert!((o[i] - 100.0 * u[i]).abs() < 1e-2);
        }
    }
}
