//! Architecture registry. Each module under `arch/` is one model family.
//! Adding a new architecture: drop a module here, dispatch in `lib.rs::load`.
//! No core changes required.

pub mod custom;
pub mod gemma4;
pub mod llama3;
