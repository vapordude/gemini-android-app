//! Architecture-agnostic LM runtime. The public API is a small `Model` trait
//! and a single `load()` entry point. Architecture impls live under `arch/`
//! and are dispatched by the GGUF `general.architecture` tag.
//!
//! The runtime is content-neutral by design: no weight allowlist, no
//! fingerprinting, no refusal layer. The application layer is responsible
//! for use-policy enforcement.

pub mod arch;
pub mod host;
pub mod kv;

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
    // TODO: real layout (per-layer k/v slabs + sliding window state).
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

pub trait Model: Send {
    /// Forward a single token. Hot loop — no allocations after warmup,
    /// no I/O, no logging.
    fn forward(&mut self, token: TokenId, kv: &mut KvCache) -> &[f32];
    fn reset(&mut self, kv: &mut KvCache);
    fn info(&self) -> RuntimeInfo;
}

#[derive(Debug)]
pub enum LoadError {
    Gguf(gguf_loader::LoadError),
    UnknownArchitecture(String),
}

impl From<gguf_loader::LoadError> for LoadError {
    fn from(e: gguf_loader::LoadError) -> Self {
        LoadError::Gguf(e)
    }
}

/// Load weights from any GGUF/safetensors file mapped read-only. Architecture
/// is chosen from the metadata header; weight content is never inspected.
pub fn load(path: &Path) -> Result<Box<dyn Model>, LoadError> {
    let header = gguf_loader::read_header(path)?;
    match header.arch_tag.as_str() {
        "gemma4" | "gemma-4" => Ok(Box::new(arch::gemma4::Gemma4Model::stub(&header))),
        other => Err(LoadError::UnknownArchitecture(other.to_string())),
    }
}
