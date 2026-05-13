//! Stable Diffusion (SD 1.5 / SDXL). UNet + CLIP text encoder + VAE.
//!
//! Stub. Real implementation lands when CLIP tokenization + UNet weight
//! binding are ready. The kernels (matmul, attention, layer norm) reused
//! from `tensor-core`; a delegate-backed matmul accelerates this path
//! for free.
