//! Vision tower preprocessing + projector.
//!
//! Pipeline for one image (per Gemma 4 multimodal spec):
//!
//! 1. **Decode**: caller delivers raw pixel bytes (R G B R G B …, u8).
//! 2. **Resize**: bicubic to `IMG_SIZE × IMG_SIZE` (e.g. 224 or 384).
//! 3. **Normalize**: subtract mean, divide by std per channel.
//! 4. **Patch embed**: split into `(IMG_SIZE/IMG_PATCH)²` patches of
//!    `IMG_PATCH × IMG_PATCH × 3`, project each to `vision_hidden`.
//! 5. **Vision transformer**: same matmul/rmsnorm/attention/swiglu kernels
//!    as the decoder, but bidirectional (no causal mask). N layers.
//! 6. **Projector**: linear from `vision_hidden` to text `HIDDEN`. Output
//!    is `N_img × HIDDEN`, ready to slot at `IMAGE_TOKEN` positions in the
//!    text prompt.
//!
//! This crate provides the preprocessing math; the transformer body
//! reuses [`gemma4_ops`] kernels and is composed by [`gemma4_model`] once
//! vision-specific weights are loaded.

#![forbid(unsafe_op_in_unsafe_fn)]

/// Vision tower architecture constants. Placeholder values reflect the
/// SigLIP-style sizing used in Gemma 3; Gemma 4's MobileNet-V5 path may
/// differ — confirm against the spec.
#[derive(Clone, Copy, Debug)]
pub struct VisionConfig {
    pub image_size: usize,        // [SPEC] — e.g. 224
    pub patch_size: usize,        // [SPEC] — e.g. 14
    pub vision_hidden: usize,     // [SPEC] — e.g. 1152
    pub num_vision_layers: usize, // [SPEC]
    pub mean: [f32; 3],           // per-channel normalization mean (RGB)
    pub std: [f32; 3],            // per-channel normalization std
}

impl VisionConfig {
    pub fn placeholder() -> Self {
        VisionConfig {
            image_size: 224,
            patch_size: 14,
            vision_hidden: 1152,
            num_vision_layers: 27,
            // ImageNet stats — Gemma 4 may use different values.
            mean: [0.485, 0.456, 0.406],
            std: [0.229, 0.224, 0.225],
        }
    }

    pub fn num_patches(&self) -> usize {
        let side = self.image_size / self.patch_size;
        side * side
    }
}

/// Bicubic resize. Input is `[H, W, 3]` row-major u8 RGB; output is
/// `[target_size, target_size, 3]` f32 in [0, 1]. We do the resize in
/// f32 to avoid integer overflow in the convolution kernel.
pub fn bicubic_resize_rgb(
    src: &[u8],
    src_h: usize,
    src_w: usize,
    target: usize,
) -> Vec<f32> {
    assert_eq!(src.len(), src_h * src_w * 3);
    let mut out = vec![0.0_f32; target * target * 3];
    let scale_y = (src_h as f32) / (target as f32);
    let scale_x = (src_w as f32) / (target as f32);
    for ty in 0..target {
        let sy = (ty as f32 + 0.5) * scale_y - 0.5;
        let iy = sy.floor() as i32;
        let fy = sy - (iy as f32);
        for tx in 0..target {
            let sx = (tx as f32 + 0.5) * scale_x - 0.5;
            let ix = sx.floor() as i32;
            let fx = sx - (ix as f32);
            for c in 0..3 {
                let mut acc = 0.0_f32;
                let mut w_sum = 0.0_f32;
                for ky in -1..=2_i32 {
                    let py = (iy + ky).clamp(0, (src_h as i32) - 1) as usize;
                    let wy = cubic(fy - (ky as f32));
                    for kx in -1..=2_i32 {
                        let px = (ix + kx).clamp(0, (src_w as i32) - 1) as usize;
                        let wx = cubic(fx - (kx as f32));
                        let w = wy * wx;
                        let v = src[(py * src_w + px) * 3 + c] as f32 / 255.0;
                        acc += v * w;
                        w_sum += w;
                    }
                }
                out[(ty * target + tx) * 3 + c] = acc / w_sum.max(1e-6);
            }
        }
    }
    out
}

/// Catmull-Rom cubic convolution weight. Standard a = -0.5.
#[inline]
fn cubic(t: f32) -> f32 {
    let a = -0.5_f32;
    let t = t.abs();
    if t < 1.0 {
        (a + 2.0) * t * t * t - (a + 3.0) * t * t + 1.0
    } else if t < 2.0 {
        a * t * t * t - 5.0 * a * t * t + 8.0 * a * t - 4.0 * a
    } else {
        0.0
    }
}

/// Subtract mean / divide by std, per channel. In-place.
pub fn normalize_rgb(image: &mut [f32], cfg: &VisionConfig) {
    for chunk in image.chunks_exact_mut(3) {
        for c in 0..3 {
            chunk[c] = (chunk[c] - cfg.mean[c]) / cfg.std[c];
        }
    }
}

/// Cut the image into `patch_size × patch_size × 3` tiles and flatten each
/// to a row of length `patch_size² × 3`. Output shape: `[num_patches, patch_size² · 3]`.
pub fn patchify(image: &[f32], cfg: &VisionConfig) -> Vec<f32> {
    let side = cfg.image_size / cfg.patch_size;
    let patch_len = cfg.patch_size * cfg.patch_size * 3;
    let mut out = vec![0.0_f32; side * side * patch_len];
    for py in 0..side {
        for px in 0..side {
            let patch_idx = py * side + px;
            for dy in 0..cfg.patch_size {
                for dx in 0..cfg.patch_size {
                    let sy = py * cfg.patch_size + dy;
                    let sx = px * cfg.patch_size + dx;
                    let src_off = (sy * cfg.image_size + sx) * 3;
                    let dst_off = patch_idx * patch_len + (dy * cfg.patch_size + dx) * 3;
                    out[dst_off]     = image[src_off];
                    out[dst_off + 1] = image[src_off + 1];
                    out[dst_off + 2] = image[src_off + 2];
                }
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cubic_weight_is_sum_to_one_ish() {
        // Sample the 4-tap cubic kernel at a few representative offsets;
        // the sum should be close to 1 for a well-behaved cubic.
        for t0 in [0.0_f32, 0.25, 0.5, 0.75] {
            let mut s = 0.0;
            for k in -1..=2_i32 {
                s += cubic(t0 - (k as f32));
            }
            assert!((s - 1.0).abs() < 1e-3, "t0={t0} sum={s}");
        }
    }

    #[test]
    fn resize_to_same_size_produces_finite_output() {
        // Bicubic with clamp + 4-tap kernel doesn't reproduce source exactly
        // (the kernel samples at half-pixel centers), but the output must
        // be a real image: same shape and all values in [0, 1].
        let src: Vec<u8> = vec![0, 0, 0, 255, 255, 255, 128, 128, 128, 64, 64, 64];
        let out = bicubic_resize_rgb(&src, 2, 2, 2);
        assert_eq!(out.len(), 2 * 2 * 3);
        for v in &out {
            assert!(v.is_finite() && (0.0..=1.0_f32).contains(v),
                "out-of-range pixel value: {v}");
        }
    }

    #[test]
    fn patchify_produces_correct_count() {
        let cfg = VisionConfig {
            image_size: 4,
            patch_size: 2,
            vision_hidden: 8,
            num_vision_layers: 1,
            mean: [0.0, 0.0, 0.0],
            std: [1.0, 1.0, 1.0],
        };
        let image: Vec<f32> = (0..4 * 4 * 3).map(|i| i as f32).collect();
        let patches = patchify(&image, &cfg);
        // 4 patches, each 2*2*3 = 12 floats → 48 total
        assert_eq!(patches.len(), 4 * 12);
        assert_eq!(cfg.num_patches(), 4);
    }

    #[test]
    fn normalize_subtracts_mean_and_scales() {
        let cfg = VisionConfig::placeholder();
        let mut image = vec![cfg.mean[0], cfg.mean[1], cfg.mean[2]]; // one pixel == mean
        normalize_rgb(&mut image, &cfg);
        // After subtraction, all values are 0 → normalized to 0 too.
        for v in image { assert!(v.abs() < 1e-6); }
    }
}
