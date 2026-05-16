//! Math-first kernel library for Gemma 4 inference.
//!
//! Every kernel here is a literal translation of the formulas in Qwen's
//! first-principles memo. Scalar reference implementations are the parity
//! oracle — they must match a PyTorch trace of the HF weights to
//! `‖ours − ref‖_∞ < 1e-4 (F32)` before any NEON specialization is allowed
//! to override them.
//!
//! No external dependencies. Pure `core` + `std`.

#![forbid(unsafe_op_in_unsafe_fn)]

pub mod matmul;
pub mod rmsnorm;
pub mod rope;
pub mod softmax;
pub mod swiglu;
pub mod attention;
pub mod sampler;
pub mod parity;

// Re-exports — flat surface keeps the call sites short.
pub use matmul::{matmul_f32, matmul_bf16_acc_f32};
pub use rmsnorm::rmsnorm_f32;
pub use rope::{rope_freqs_f32, rope_apply_f32};
pub use softmax::softmax_inplace_f32;
pub use swiglu::{silu_f32, swiglu_f32};
pub use attention::gqa_attention_f32;
pub use sampler::sample_token;
