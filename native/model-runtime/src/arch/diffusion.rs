//! Diffusion-model architectures (iterative denoising). All implement
//! the `ImageModel` trait.
//!
//! Reserved for Stable Diffusion family (SD 1.5 / SDXL / SD 3 / Flux),
//! plus any custom diffusion architectures slotted in by the operator.
//!
//! v0 ships only the namespace + placeholder so the runtime stays
//! architecture-extensible without committing to a specific
//! implementation. Real SD/SDXL UNet impl is a separate workstream.

pub mod custom;
pub mod stable_diffusion;
