//! Architecture-agnostic LM runtime. The public API is a small `Model` trait
//! and a single `load()` entry point. Architecture impls live under `arch/`
//! and are dispatched by the GGUF `general.architecture` tag.
//!
//! Content-neutral: weights are opaque; metadata drives everything.

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

pub trait Model: Send {
    fn forward(&mut self, token: TokenId, kv: &mut KvCache) -> &[f32];
    fn reset(&mut self, kv: &mut KvCache);
    fn info(&self) -> RuntimeInfo;
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

/// Load weights. Architecture comes from `general.architecture` metadata;
/// weight content is never inspected. If your future private model
/// declares its own architecture tag, register it in the match below.
pub fn load(path: &Path) -> Result<Box<dyn Model>, LoadError> {
    let gguf = gguf_loader::read(path)?;
    let arch = gguf
        .arch_tag()
        .ok_or(LoadError::MissingMetadata("general.architecture"))?;
    match arch {
        "gemma4" | "gemma-4" | "gemma" | "gemma2" | "gemma3" => {
            Ok(Box::new(arch::gemma4::Gemma4Model::from_gguf(&gguf)?))
        }
        other => Err(LoadError::UnknownArchitecture(other.to_string())),
    }
}
