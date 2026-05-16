//! Tensor primitives, dtypes, and read-only mmap over weight files.
//!
//! This crate is the foundation everything else builds on. It speaks **shapes
//! and strides** — the index formula from Qwen's first-principles memo:
//!
//! ```text
//! index(i_1, ..., i_n) = Σ_{k=1..n} i_k · Π_{j=k+1..n} d_j
//! ```
//!
//! No external dependencies. `mmap` is reached through raw libc symbols
//! declared in [`mmap`] rather than the `libc` crate.

#![forbid(unsafe_op_in_unsafe_fn)]

pub mod tensor;
pub mod dtype;
pub mod alloc;
pub mod mmap;

pub use tensor::{Tensor, TensorMut, Shape};
pub use dtype::{Dtype, bf16_to_f32, f32_to_bf16, f16_to_f32, f32_to_f16};
