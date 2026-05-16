//! IEEE 754 binary16 helpers. Pure Rust, no deps. Rust's stable `f16` is
//! available but we keep our own to (a) work on older toolchains and
//! (b) avoid any std-feature drift.

/// Convert an IEEE 754 binary16 bit-pattern to f32.
pub fn f16_to_f32(bits: u16) -> f32 {
    let sign = (bits >> 15) & 0x1;
    let exp = ((bits >> 10) & 0x1f) as i32;
    let frac = (bits & 0x3ff) as u32;
    let magnitude = match exp {
        0 => {
            if frac == 0 {
                0.0
            } else {
                // Subnormal: value = frac * 2^-24
                (frac as f32) * (1.0f32 / (1 << 24) as f32)
            }
        }
        31 => {
            if frac == 0 {
                f32::INFINITY
            } else {
                f32::NAN
            }
        }
        _ => {
            let mantissa = ((1u32 << 23) | (frac << 13)) as f32 / (1u32 << 23) as f32;
            let e = exp - 15;
            mantissa * 2f32.powi(e)
        }
    };
    if sign == 1 {
        -magnitude
    } else {
        magnitude
    }
}

/// f32 → f16 bits, for test fixtures. Not used in production paths.
/// Panics on values that don't round-trip cleanly via the simple path
/// (denormals, NaN, inf, overflow).
#[cfg(test)]
pub(crate) fn f32_to_f16_bits(v: f32) -> u16 {
    if v == 0.0 {
        return if v.is_sign_negative() { 0x8000 } else { 0x0000 };
    }
    let bits = v.to_bits();
    let sign = ((bits >> 31) & 0x1) as u16;
    let exp = ((bits >> 23) & 0xff) as i32 - 127 + 15;
    let frac = (bits >> 13) & 0x3ff;
    assert!(exp > 0 && exp < 31, "f32_to_f16_bits out of range: v={v}");
    (sign << 15) | ((exp as u16) << 10) | (frac as u16)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn f16_zero() {
        assert_eq!(f16_to_f32(0x0000), 0.0);
        assert_eq!(f16_to_f32(0x8000), -0.0);
    }

    #[test]
    fn f16_one() {
        // 0 01111 0000000000 = 1.0
        assert_eq!(f16_to_f32(0x3C00), 1.0);
        assert_eq!(f16_to_f32(0xBC00), -1.0);
    }

    #[test]
    fn f16_two() {
        // 0 10000 0000000000 = 2.0
        assert_eq!(f16_to_f32(0x4000), 2.0);
    }

    #[test]
    fn f16_half() {
        // 0 01110 0000000000 = 0.5
        assert_eq!(f16_to_f32(0x3800), 0.5);
    }

    #[test]
    fn f16_infinity() {
        assert!(f16_to_f32(0x7C00).is_infinite());
        assert!(f16_to_f32(0xFC00).is_infinite());
    }

    #[test]
    fn f16_subnormal_smallest() {
        // 0 00000 0000000001 = 2^-24
        let v = f16_to_f32(0x0001);
        assert!((v - (1.0f32 / (1 << 24) as f32)).abs() < 1e-12);
    }
}
