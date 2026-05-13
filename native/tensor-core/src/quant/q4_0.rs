//! Q4_0: 32-element blocks, each with a single f16 scale and 16 packed nibbles.

pub const BLOCK: usize = 32;
pub const BYTES_PER_BLOCK: usize = 2 /* f16 scale */ + BLOCK / 2;

pub fn dequantize_block(_src: &[u8], _dst: &mut [f32]) {
    // TODO: implement reference + SIMD variants.
}
