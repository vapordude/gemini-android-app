//! Pure-Rust tensor math. The only crate in the workspace where `unsafe`
//! SIMD intrinsics are allowed to live. Public API is safe; intrinsics are
//! gated by `cfg(target_arch = ...)` and runtime feature detection.
//!
//! Dependencies: `libm` only.

#![deny(unsafe_op_in_unsafe_fn)]

pub mod delegate;
pub mod half;
pub mod isa;
pub mod kernels;
pub mod quant;
pub mod sched;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IsaTier {
    Scalar,
    NeonDotProd,
    NeonI8mm,
    Avx2,
    Avx512,
}

impl IsaTier {
    pub fn tag(self) -> &'static str {
        match self {
            IsaTier::Scalar => "scalar",
            IsaTier::NeonDotProd => "neon-dotprod",
            IsaTier::NeonI8mm => "neon-i8mm",
            IsaTier::Avx2 => "avx2",
            IsaTier::Avx512 => "avx512",
        }
    }
}
