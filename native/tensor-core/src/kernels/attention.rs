//! Scaled dot-product attention. Real impl (sliding-window for Gemma 4)
//! lands with arch/gemma4.

pub fn attention_f32(
    _q: &[f32],
    _k: &[f32],
    _v: &[f32],
    _out: &mut [f32],
    _seq_len: usize,
    _head_dim: usize,
    _window: Option<usize>,
) {
    // TODO: implement.
}
