/// Numerically stable softmax over a slice, in place.
pub fn softmax_f32(x: &mut [f32]) {
    let mut max = f32::NEG_INFINITY;
    for &v in x.iter() {
        if v > max {
            max = v;
        }
    }
    if !max.is_finite() {
        // All -inf: leave zeros so the consumer can detect "no contribution".
        for v in x.iter_mut() {
            *v = 0.0;
        }
        return;
    }
    let mut sum = 0.0f32;
    for v in x.iter_mut() {
        *v = (*v - max).exp();
        sum += *v;
    }
    let inv = 1.0f32 / sum;
    for v in x.iter_mut() {
        *v *= inv;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sums_to_one() {
        let mut x = vec![1.0, 2.0, 3.0, 4.0];
        softmax_f32(&mut x);
        let sum: f32 = x.iter().sum();
        assert!((sum - 1.0).abs() < 1e-5);
        for v in &x {
            assert!(*v >= 0.0);
        }
    }

    #[test]
    fn handles_all_neg_inf() {
        let mut x = vec![f32::NEG_INFINITY; 3];
        softmax_f32(&mut x);
        assert!(x.iter().all(|v| *v == 0.0));
    }

    #[test]
    fn handles_large_inputs() {
        let mut x = vec![1000.0, 1000.0, 1000.0];
        softmax_f32(&mut x);
        for v in x {
            assert!((v - 1.0 / 3.0).abs() < 1e-5);
        }
    }
}
