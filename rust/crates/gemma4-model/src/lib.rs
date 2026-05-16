//! Gemma 4 E2B decoder assembly.
//!
//! Decoder block (per HF model card):
//!
//! ```text
//! h = rmsnorm_pre_attn(x)              // norm 1 of 4
//! h = gqa_attention(h, kv_cache)
//! h = rmsnorm_post_attn(h)              // norm 2 of 4  ← Gemma 4 double-norm
//! x = x + h                             // residual after BOTH attn norms
//! h = rmsnorm_pre_ffn(x)                // norm 3 of 4
//! h = swiglu(h)
//! h = rmsnorm_post_ffn(h)               // norm 4 of 4  ← Gemma 4 double-norm
//! x = x + h
//! x = ple_inject(x, token_id, layer)    // Per-Layer Embedding repair channel
//! ```
//!
//! Layers `0..KV_SHARED_LAYERS` alias the previous layer's K/V cache —
//! the spec's "selective activation" mechanism that gives the E2B variant
//! its "effective 2B" parameter count.
//!
//! Constants tagged `[SPEC]` need the exact values from the shipped
//! `config.json`; placeholders live in [`config`].

#![forbid(unsafe_op_in_unsafe_fn)]

pub mod config;
pub mod kv_cache;
pub mod block;
pub mod weights;

pub use config::Gemma4Config;
pub use kv_cache::KvCache;
pub use block::decoder_block_f32;
pub use weights::LayerWeights;
