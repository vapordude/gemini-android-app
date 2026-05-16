//! Math hotpath kernels. Each kernel has a scalar reference implementation
//! plus cfg-gated SIMD variants. Real implementations land in dedicated
//! commits; v0 ships scalar references only.

pub mod attention;
pub mod matmul;
pub mod rmsnorm;
pub mod rope;
pub mod softmax;
pub mod swiglu;
