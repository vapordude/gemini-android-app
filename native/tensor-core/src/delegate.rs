//! Compute delegate seam. The runtime defaults to the in-tree pure-Rust
//! CPU path. A `Delegate` impl can intercept kernel calls and dispatch
//! them to a vendor accelerator (Qualcomm Hexagon QNN, Apple Neural
//! Engine, Mali GPU via Vulkan, Adreno via OpenCL, etc.).
//!
//! Why a trait, not a feature flag: NPU/GPU availability is a *runtime*
//! property of the device. The same APK is installed on a phone with a
//! Hexagon NPU and one without. At init the runtime probes for available
//! delegates and picks the highest-priority one that initializes
//! successfully; CPU stays as the always-available fallback.
//!
//! Why this trait lives in `tensor-core` and not in a separate crate:
//! delegates dispatch on kernels, and the canonical kernels live here.
//! Concrete vendor impls (which DO pull in external libs) live in their
//! own crates and are gated by cargo features so the default build
//! stays dep-free.
//!
//! Stable Diffusion / image-gen note: diffusion models share these same
//! matmul / attention kernels with LMs. A delegate that accelerates the
//! UNet inner loop accelerates LM decode for free, and vice versa. The
//! `arch/diffusion/` modules live next to `arch/lm/*` in model-runtime.

use crate::IsaTier;

#[derive(Debug, Clone, Copy)]
pub enum DelegateKind {
    Cpu,
    HexagonQnn,
    NeuralEngine,
    Vulkan,
    OpenCl,
    Custom(&'static str),
}

impl DelegateKind {
    pub fn tag(self) -> &'static str {
        match self {
            Self::Cpu => "cpu",
            Self::HexagonQnn => "qnn",
            Self::NeuralEngine => "neural-engine",
            Self::Vulkan => "vulkan",
            Self::OpenCl => "opencl",
            Self::Custom(t) => t,
        }
    }
}

/// Capability advertisement so the runtime can choose which delegate
/// owns a given op type. A delegate that returns `false` for an op falls
/// back to the CPU path.
#[derive(Debug, Default, Clone, Copy)]
pub struct DelegateCaps {
    pub matmul_f32: bool,
    pub matvec_f32: bool,
    pub attention_f32: bool,
    pub quant_dequant: bool,
}

/// Minimum surface a delegate must implement. v0 keeps this small;
/// kernel-specific methods get added behind feature flags as concrete
/// delegates land.
pub trait Delegate: Send + Sync {
    fn kind(&self) -> DelegateKind;
    fn caps(&self) -> DelegateCaps;
    fn isa(&self) -> IsaTier {
        IsaTier::Scalar
    }

    /// Optional matmul override. Default: signal not-handled so the
    /// caller falls back to the in-tree scalar/SIMD path.
    fn matmul_f32(
        &self,
        _a: &[f32],
        _b: &[f32],
        _c: &mut [f32],
        _m: usize,
        _n: usize,
        _k: usize,
    ) -> bool {
        false
    }
}

/// The always-available CPU "delegate" — a marker so call sites can be
/// uniform. The real CPU path lives in `kernels/`.
pub struct CpuDelegate;

impl Delegate for CpuDelegate {
    fn kind(&self) -> DelegateKind {
        DelegateKind::Cpu
    }
    fn caps(&self) -> DelegateCaps {
        DelegateCaps {
            matmul_f32: true,
            matvec_f32: true,
            attention_f32: true,
            quant_dequant: true,
        }
    }
    fn isa(&self) -> IsaTier {
        crate::isa::detect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cpu_delegate_advertises_caps() {
        let d = CpuDelegate;
        assert!(matches!(d.kind(), DelegateKind::Cpu));
        assert!(d.caps().matmul_f32);
    }

    #[test]
    fn default_matmul_returns_not_handled() {
        struct Noop;
        impl Delegate for Noop {
            fn kind(&self) -> DelegateKind {
                DelegateKind::Custom("noop")
            }
            fn caps(&self) -> DelegateCaps {
                DelegateCaps::default()
            }
        }
        let d = Noop;
        let a = vec![1.0; 4];
        let b = vec![1.0; 4];
        let mut c = vec![0.0; 1];
        assert!(!d.matmul_f32(&a, &b, &mut c, 1, 1, 4));
    }
}
