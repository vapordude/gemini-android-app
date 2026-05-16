//! Runtime CPU feature detection. Called once at engine init.

use crate::IsaTier;

pub fn detect() -> IsaTier {
    #[cfg(target_arch = "aarch64")]
    {
        if std::arch::is_aarch64_feature_detected!("i8mm") {
            return IsaTier::NeonI8mm;
        }
        if std::arch::is_aarch64_feature_detected!("dotprod") {
            return IsaTier::NeonDotProd;
        }
    }
    #[cfg(target_arch = "x86_64")]
    {
        if is_x86_feature_detected!("avx512f") {
            return IsaTier::Avx512;
        }
        if is_x86_feature_detected!("avx2") {
            return IsaTier::Avx2;
        }
    }
    IsaTier::Scalar
}
