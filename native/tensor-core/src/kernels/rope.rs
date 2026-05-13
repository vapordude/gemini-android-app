//! Rotary Position Embedding. Real implementation lands with the gemma4 arch.

pub fn rope_apply_f32(_q: &mut [f32], _k: &mut [f32], _pos: usize, _head_dim: usize, _base: f32) {
    // TODO: implement when arch/gemma4/model.rs lands.
}
