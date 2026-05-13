/// SwiGLU activation: `out = silu(gate) * up`.
pub fn swiglu_f32(gate: &[f32], up: &[f32], out: &mut [f32]) {
    debug_assert_eq!(gate.len(), up.len());
    debug_assert_eq!(gate.len(), out.len());
    for i in 0..gate.len() {
        let g = gate[i];
        let silu = g / (1.0 + libm::expf(-g));
        out[i] = silu * up[i];
    }
}
