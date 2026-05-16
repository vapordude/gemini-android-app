//! Math-parity helpers. Loads PyTorch-traced reference tensors from
//! `safetensors` fixtures and compares them against kernel outputs with
//! dtype-appropriate tolerances.
//!
//! Tolerances are deliberately conservative; tightening them is the right
//! direction once specific kernels are profiled.

use gemma4_core::SafeTensors;

/// Per-dtype absolute tolerances. Tightening these is the right direction
/// after verifying with full-precision PyTorch traces.
pub const TOL_F32: f32 = 1.0e-4;
pub const TOL_BF16: f32 = 5.0e-3;
pub const TOL_Q4: f32 = 2.0e-2;

/// Assert `‖actual − expected‖_∞ < tol`. Reports the worst-case index and
/// magnitude so a failing test points directly at the regression.
pub fn assert_close(actual: &[f32], expected: &[f32], tol: f32, label: &str) {
    assert_eq!(actual.len(), expected.len(),
        "[{label}] length mismatch: actual={} expected={}",
        actual.len(), expected.len());
    let mut worst_i = 0usize;
    let mut worst = 0.0_f32;
    for (i, (&a, &e)) in actual.iter().zip(expected.iter()).enumerate() {
        let d = (a - e).abs();
        if d > worst { worst = d; worst_i = i; }
    }
    assert!(worst < tol,
        "[{label}] max abs diff {worst:.6e} at index {worst_i}: actual={} expected={}",
        actual[worst_i], expected[worst_i]);
}

/// Load a single F32 tensor by name from a safetensors fixture buffer.
pub fn load_f32(bytes: &[u8], name: &str) -> Result<Vec<f32>, &'static str> {
    let st = SafeTensors::parse(bytes)?;
    st.f32(name).ok_or("tensor not found or wrong dtype")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn assert_close_within_tolerance_passes() {
        let actual = [1.0_f32, 2.0, 3.0];
        let expected = [1.00001_f32, 2.00002, 2.99999];
        assert_close(&actual, &expected, TOL_F32, "smoke");
    }

    #[test]
    #[should_panic(expected = "max abs diff")]
    fn assert_close_outside_tolerance_panics() {
        let actual = [1.0_f32, 2.0, 3.0];
        let expected = [1.5_f32, 2.0, 3.0];
        assert_close(&actual, &expected, TOL_F32, "regression");
    }
}
