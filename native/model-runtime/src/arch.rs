//! Architecture registry. Each module under `arch/<family>/<tag>/` is one
//! model architecture. Two families today:
//!
//! - `lm/` — autoregressive language models (gemma4, llama3, custom…)
//! - `diffusion/` — iterative-denoising image models (Stable Diffusion
//!   class). Reuses the tensor-core kernels + delegate seam; the
//!   runtime shape is just different (UNet step instead of token forward).
//!
//! Adding a new architecture:
//! 1. Drop a module under arch/<family>/<your_tag>/.
//! 2. Implement `LanguageModel` or `ImageModel` (`lib.rs`).
//! 3. Add a dispatch arm in `lib.rs::load`.
//!
//! No core changes required.

pub mod diffusion;
pub mod lm;
