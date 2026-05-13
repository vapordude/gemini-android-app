//! Q8_0: 32-element blocks, single f16 scale, signed i8 weights.

pub const BLOCK: usize = 32;
pub const BYTES_PER_BLOCK: usize = 2 + BLOCK;

pub fn dequantize_block(_src: &[u8], _dst: &mut [f32]) {
    // TODO: implement.
}
