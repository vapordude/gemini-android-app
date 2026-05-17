//! Architecture-agnostic model runtime. Two model families:
//!
//! 1. **Language models** (`LanguageModel` + `arch/lm/*`): autoregressive
//!    decode, one logits vector per `forward(token)` call. Used by the
//!    agent loop and chat.
//!
//! 2. **Diffusion models** (`ImageModel` + `arch/diffusion/*`): iterative
//!    denoising over a latent tensor, `step(latent, t, cond)`. Used for
//!    Stable-Diffusion-class image generation. Shares the same kernel
//!    set + delegate seam as LMs; an NPU/GPU delegate that accelerates
//!    matmul/attention helps both.
//!
//! Both load paths read everything from GGUF metadata — no guessing.
//! The runtime is content-neutral and architecture-extensible: drop a
//! module under `arch/<family>/<tag>/` and add a dispatch arm here.

#![deny(unsafe_op_in_unsafe_fn)]

pub mod arch;
pub mod host;
pub mod kv;
pub mod tokenizer;

use std::path::Path;
use tensor_core::IsaTier;

pub type TokenId = u32;

#[derive(Debug, Clone)]
pub struct RuntimeInfo {
    pub version: &'static str,
    pub arch_tag: String,
    pub isa: IsaTier,
    pub threads: usize,
    pub vocab_size: usize,
    pub context_length: usize,
}

pub struct KvCache {
    pub seq_len: usize,
}

impl KvCache {
    pub fn new() -> Self {
        Self { seq_len: 0 }
    }
}

impl Default for KvCache {
    fn default() -> Self {
        Self::new()
    }
}

pub trait LanguageModel: Send {
    fn forward(&mut self, token: TokenId, kv: &mut KvCache) -> &[f32];
    fn reset(&mut self, kv: &mut KvCache);
    fn info(&self) -> RuntimeInfo;
}

/// Iterative-denoising image model. `step` advances the latent one
/// diffusion timestep, conditioned on text embeddings.
pub trait ImageModel: Send {
    fn step(&mut self, latent: &mut [f32], t: u32, cond: &[f32]);
    fn decode_vae(&mut self, latent: &[f32], out_rgb: &mut [u8]);
    fn info(&self) -> RuntimeInfo;
}

/// Top-level discriminator returned by `load()`. Callers pattern-match.
pub enum LoadedModel {
    Language(Box<dyn LanguageModel>),
    Image(Box<dyn ImageModel>),
}

#[derive(Debug)]
pub enum LoadError {
    Gguf(gguf_loader::LoadError),
    UnknownArchitecture(String),
    MissingMetadata(&'static str),
}

impl From<gguf_loader::LoadError> for LoadError {
    fn from(e: gguf_loader::LoadError) -> Self {
        LoadError::Gguf(e)
    }
}

/// Load any model. Architecture comes from `general.architecture`
/// metadata. Dispatch is purely on architecture name — the
/// `general.type` field that earlier revisions consulted is not
/// reliably populated by upstream GGUF packers (a Gemma 4 quant in
/// the wild often has no `general.type` at all, or has it set to the
/// architecture name, or to an empty string). Gating on it produced
/// false `UnknownArchitecture` errors for files whose architecture
/// *was* recognised. When diffusion architectures land, they grow a
/// new arm here.
///
/// The returned model has every tensor dequantized to F32 in memory —
/// see [`arch::lm::gemma4::Gemma4Model::load`] for the memory caveat.
pub fn load(path: &Path) -> Result<LoadedModel, LoadError> {
    let gguf = gguf_loader::GgufBytes::read(path)?;
    let arch = gguf
        .file
        .arch_tag()
        .ok_or(LoadError::MissingMetadata("general.architecture"))?
        .to_string();

    // D1 — model loaded. Per the diag plan, capture arch + ISA + thread
    // count so the harness can confirm what actually ran.
    diagnostics::probe!(diagnostics::Probe::ModelLoaded {
        arch_tag: arch.clone(),
        isa: tensor_core::isa::detect().tag().to_string(),
        threads: 1,
    });

    match classify_arch(arch.as_str()) {
        Ok("lm/gemma4") => Ok(LoadedModel::Language(Box::new(
            arch::lm::gemma4::Gemma4Model::load(&gguf)?,
        ))),
        // Unreachable today; lands when classify_arch grows new arms.
        Ok(other) => Err(LoadError::UnknownArchitecture(format!(
            "internal: unhandled dispatch arm '{other}'"
        ))),
        Err(other) => Err(LoadError::UnknownArchitecture(other.to_string())),
    }
}

/// Inspect a GGUF file without binding any weights. Returns the arch tag
/// and (vocab_size, context_length, hidden_size, n_layers). Used by the
/// inference façade to report metadata before the (potentially slow)
/// full load.
pub fn probe(path: &Path) -> Result<arch::lm::gemma4::Gemma4Config, LoadError> {
    let gguf = gguf_loader::read(path)?;
    arch::lm::gemma4::Gemma4Config::from_gguf(&gguf)
}

/// Backwards-compatible LM-only load, for callers that only deal with
/// language models. Returns an error if the file is an image model.
pub fn load_language(path: &Path) -> Result<Box<dyn LanguageModel>, LoadError> {
    match load(path)? {
        LoadedModel::Language(m) => Ok(m),
        LoadedModel::Image(_) => Err(LoadError::UnknownArchitecture(
            "expected a language model, got an image model".to_string(),
        )),
    }
}

/// Classify a `general.architecture` string into the loader arm that
/// should handle it. Pulled out of `load()` so the dispatch table is
/// independently testable — a regression that re-introduces a gate
/// (like the old `general.type` check that broke real-world GGUFs)
/// surfaces here without needing a synthetic model file.
#[doc(hidden)]
pub fn classify_arch(arch: &str) -> Result<&'static str, &str> {
    match arch {
        "gemma4" | "gemma-4" | "gemma" | "gemma2" | "gemma3" => Ok("lm/gemma4"),
        other => Err(other),
    }
}

#[cfg(test)]
mod dispatch_tests {
    use super::*;

    #[test]
    fn gemma4_dispatches_to_lm_gemma4() {
        assert_eq!(classify_arch("gemma4"), Ok("lm/gemma4"));
    }

    #[test]
    fn gemma_family_strings_all_dispatch_to_lm_gemma4() {
        for arch in &["gemma", "gemma2", "gemma3", "gemma4", "gemma-4"] {
            assert_eq!(
                classify_arch(arch),
                Ok("lm/gemma4"),
                "arch {arch:?} should dispatch to lm/gemma4"
            );
        }
    }

    #[test]
    fn unknown_arch_returns_the_offending_string() {
        assert_eq!(classify_arch("llama3"), Err("llama3"));
        assert_eq!(classify_arch(""), Err(""));
    }
}
