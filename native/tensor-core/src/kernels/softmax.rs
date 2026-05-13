/// Numerically stable softmax over a slice.
pub fn softmax_f32(x: &mut [f32]) {
    let mut max = f32::NEG_INFINITY;
    for &v in x.iter() {
        if v > max {
            max = v;
        }
    }
    let mut sum = 0.0f32;
    for v in x.iter_mut() {
        *v = libm::expf(*v - max);
        sum += *v;
    }
    let inv = 1.0f32 / sum;
    for v in x.iter_mut() {
        *v *= inv;
    }
}
