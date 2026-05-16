//! Q4_K_M: k-quant 4-bit. Super-block of 256 elements grouped into 8
//! sub-blocks of 32. Each super-block:
//!   2 bytes — f16 d (super-scale for scales)
//!   2 bytes — f16 dmin (super-scale for mins)
//!  12 bytes — packed 6-bit scales + 6-bit mins for the 8 sub-blocks
//! 128 bytes — packed 4-bit weights (256 nibbles)
//!
//! Per sub-block `s` with weight nibble `q`:
//!   value = d * scale_s * q  -  dmin * min_s
//!
//! v0 leaves the dequant body as TODO so we can land the surface and
//! exercise the dispatch logic. Real impl requires the exact 6-bit
//! scale/min packing layout used by llama.cpp.

pub const SUPER_BLOCK: usize = 256;
pub const BYTES_PER_SUPER_BLOCK: usize = 2 + 2 + 12 + 128;

pub fn dequantize_block(_src: &[u8], _dst: &mut [f32]) {
    // TODO: real impl. See llama.cpp `dequantize_row_q4_K`.
}
