//! Generic row-major tensor view. Carries shape and strides separately so
//! reshape/transpose are O(1) view changes without touching memory.

use core::ops::Range;

/// Shape: rank-bounded fixed-size storage so we don't allocate per Tensor.
/// 6 covers everything we need (a batch of [B, H, S, Dh] attention scores
/// reshapes through 5 dims; 6 leaves slack).
pub const MAX_RANK: usize = 6;

#[derive(Clone, Copy)]
pub struct Shape {
    pub rank: usize,
    pub dims: [usize; MAX_RANK],
}

impl Shape {
    pub fn new(dims: &[usize]) -> Self {
        assert!(dims.len() <= MAX_RANK, "rank exceeds MAX_RANK");
        let mut out = [0usize; MAX_RANK];
        for (i, &d) in dims.iter().enumerate() {
            out[i] = d;
        }
        Shape { rank: dims.len(), dims: out }
    }

    pub fn as_slice(&self) -> &[usize] {
        &self.dims[..self.rank]
    }

    pub fn numel(&self) -> usize {
        self.as_slice().iter().product()
    }
}

/// Read-only tensor view. The lifetime ties it to the backing buffer
/// (mmap'd weight file, arena-allocated activation, or a `Vec`).
pub struct Tensor<'a, T> {
    pub data: &'a [T],
    pub shape: Shape,
    pub strides: [usize; MAX_RANK],
}

/// Mutable tensor view — required for in-place kernels (RMSNorm, RoPE, etc.).
pub struct TensorMut<'a, T> {
    pub data: &'a mut [T],
    pub shape: Shape,
    pub strides: [usize; MAX_RANK],
}

impl<'a, T> Tensor<'a, T> {
    pub fn from_slice(data: &'a [T], dims: &[usize]) -> Self {
        let shape = Shape::new(dims);
        let strides = row_major_strides(&shape);
        assert_eq!(
            data.len(),
            shape.numel(),
            "data len doesn't match shape product"
        );
        Tensor { data, shape, strides }
    }

    /// Index formula from the math spec:
    /// `index(i_1, ..., i_n) = Σ i_k · Π_{j>k} d_j`.
    pub fn offset(&self, idx: &[usize]) -> usize {
        debug_assert_eq!(idx.len(), self.shape.rank, "index rank mismatch");
        let mut off = 0usize;
        for (k, &i_k) in idx.iter().enumerate().take(self.shape.rank) {
            debug_assert!(i_k < self.shape.dims[k], "index out of bounds");
            off += i_k * self.strides[k];
        }
        off
    }

    /// Row-major view over a contiguous slice of the leading axis. Used to
    /// iterate over rows / heads / layers without allocating.
    pub fn row(&'a self, i: usize) -> Tensor<'a, T> {
        let inner_dims = &self.shape.as_slice()[1..];
        let inner_numel: usize = inner_dims.iter().product();
        let start = i * inner_numel;
        Tensor::from_slice(&self.data[start..start + inner_numel], inner_dims)
    }
}

impl<'a, T> TensorMut<'a, T> {
    pub fn from_slice_mut(data: &'a mut [T], dims: &[usize]) -> Self {
        let shape = Shape::new(dims);
        let strides = row_major_strides(&shape);
        assert_eq!(data.len(), shape.numel(), "data len doesn't match shape product");
        TensorMut { data, shape, strides }
    }

    pub fn as_ref(&self) -> Tensor<'_, T> {
        Tensor { data: &*self.data, shape: self.shape, strides: self.strides }
    }
}

/// Row-major contiguous strides: `strides[k] = Π_{j > k} dims[j]`.
pub fn row_major_strides(shape: &Shape) -> [usize; MAX_RANK] {
    let mut s = [0usize; MAX_RANK];
    if shape.rank == 0 { return s; }
    s[shape.rank - 1] = 1;
    for k in (0..shape.rank - 1).rev() {
        s[k] = s[k + 1] * shape.dims[k + 1];
    }
    s
}

/// Convenience: yield the flat range that maps to a sub-tensor along the
/// leading axis. Useful for KV-cache slicing.
pub fn row_range(shape: &Shape, leading: Range<usize>) -> Range<usize> {
    let inner: usize = shape.as_slice()[1..].iter().product();
    leading.start * inner..leading.end * inner
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strides_match_index_formula() {
        // Shape [2, 3, 4] -> strides [12, 4, 1]
        let s = Shape::new(&[2, 3, 4]);
        let strides = row_major_strides(&s);
        assert_eq!(&strides[..3], &[12, 4, 1]);
    }

    #[test]
    fn offset_matches_spec() {
        // For T ∈ R^{d1×d2×d3}, idx(i1,i2,i3) = i1*d2*d3 + i2*d3 + i3
        let data = (0..24u32).collect::<Vec<_>>();
        let t = Tensor::from_slice(&data, &[2, 3, 4]);
        assert_eq!(t.offset(&[1, 2, 3]), 12 + 2 * 4 + 3);
        assert_eq!(t.data[t.offset(&[1, 2, 3])], 23);
    }
}
