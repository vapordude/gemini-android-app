//! Dtype enum + software BF16/F16 <-> F32 conversions.
//!
//! Conversions are intentionally pure software (bit manipulation only) so we
//! don't depend on hardware bf16/f16 instructions. Plenty fast for inference
//! when called inside a tight loop and bottlenecked on matmul throughput.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Dtype {
    F32,
    F16,
    BF16,
    Int8,
    Q4KM,
}

impl Dtype {
    pub fn bytes_per_element(self) -> usize {
        match self {
            Dtype::F32 => 4,
            Dtype::F16 | Dtype::BF16 => 2,
            Dtype::Int8 => 1,
            // Q4_K_M stores 4 bits per weight + 16 bytes of metadata per
            // 256-element block. Approximate; exact accounting is in
            // gemma4-quant.
            Dtype::Q4KM => 0,
        }
    }
}

/// BF16 -> F32 by zero-padding the mantissa. The BF16 bit layout is identical
/// to F32's top 16 bits.
#[inline]
pub fn bf16_to_f32(bits: u16) -> f32 {
    f32::from_bits((bits as u32) << 16)
}

/// F32 -> BF16 with round-to-nearest-even. Cheaper than a generic narrow
/// because BF16's exponent matches F32's; only the mantissa needs rounding.
#[inline]
pub fn f32_to_bf16(x: f32) -> u16 {
    let bits = x.to_bits();
    // NaN: preserve sign + payload's high bit, ensure mantissa is non-zero.
    if (bits & 0x7fff_ffff) > 0x7f80_0000 {
        return (bits >> 16) as u16 | 0x0040;
    }
    // Round half to even.
    let lsb = (bits >> 16) & 1;
    let rounding_bias = 0x7fff + lsb;
    ((bits + rounding_bias) >> 16) as u16
}

/// IEEE 754 half-precision (binary16) -> F32. Used for vision-tower weights
/// when they ship as fp16 instead of bf16.
#[inline]
pub fn f16_to_f32(bits: u16) -> f32 {
    let sign = (bits as u32 & 0x8000) << 16;
    let exp = (bits as u32 >> 10) & 0x1f;
    let frac = bits as u32 & 0x3ff;

    if exp == 0 {
        if frac == 0 {
            return f32::from_bits(sign); // ±0
        }
        // Subnormal — normalize.
        let mut e = 1i32;
        let mut f = frac;
        while (f & 0x400) == 0 {
            f <<= 1;
            e -= 1;
        }
        let exp32 = (127 - 15 + e) as u32;
        return f32::from_bits(sign | (exp32 << 23) | ((f & 0x3ff) << 13));
    }
    if exp == 0x1f {
        // Inf / NaN
        return f32::from_bits(sign | 0x7f80_0000 | (frac << 13));
    }
    let exp32 = (exp + (127 - 15)) << 23;
    f32::from_bits(sign | exp32 | (frac << 13))
}

#[inline]
pub fn f32_to_f16(x: f32) -> u16 {
    let bits = x.to_bits();
    let sign = ((bits >> 16) & 0x8000) as u16;
    let exp = ((bits >> 23) & 0xff) as i32 - 127 + 15;
    let frac = bits & 0x7f_ffff;

    if exp <= 0 {
        if exp < -10 {
            return sign; // Underflow to zero.
        }
        // Subnormal half.
        let frac = (frac | 0x80_0000) >> (1 - exp);
        let rounded = (frac + 0x1000) >> 13;
        return sign | rounded as u16;
    }
    if exp >= 0x1f {
        if (bits & 0x7fff_ffff) > 0x7f80_0000 {
            // NaN
            return sign | 0x7e00 | ((frac >> 13) as u16);
        }
        return sign | 0x7c00; // ±Inf
    }
    let mantissa = (frac + 0x1000) >> 13; // round-to-nearest-even
    sign | ((exp as u16) << 10) | mantissa as u16
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bf16_round_trip_preserves_top_bits() {
        // 1.0_f32 = 0x3f800000 -> bf16 0x3f80 -> 1.0
        let bits = f32_to_bf16(1.0);
        assert_eq!(bits, 0x3f80);
        assert_eq!(bf16_to_f32(bits), 1.0);
    }

    #[test]
    fn bf16_rounds_half_to_even() {
        // 1.0 + 1ulp in bf16 = 1.0 + 2^-7 ≈ 1.0078125
        let v = 1.0 + (1.0 / 128.0);
        let r = bf16_to_f32(f32_to_bf16(v));
        assert!((r - v).abs() < 1e-3);
    }

    #[test]
    fn f16_round_trip_simple_values() {
        for v in [0.0f32, 1.0, -1.0, 0.5, 2.0, 1.5, -1.5] {
            let b = f32_to_f16(v);
            let r = f16_to_f32(b);
            assert!((r - v).abs() < 1e-3, "v={v} r={r}");
        }
    }
}
