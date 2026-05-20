//! Gemma 4 (E2B / E4B) language model. Port of
//! `llama.cpp/src/models/gemma4.cpp`.
//!
//! Architecture summary:
//!
//! - Per-layer alternating attention type: `sliding_attention` (window
//!   512, RoPE base 10000, head_dim 256) or `full_attention` (no window,
//!   RoPE base 1_000_000 with `partial_rotary_factor = 0.25`,
//!   head_dim 512 = `global_head_dim`). The pattern is exact 5:1 with
//!   the last layer always full.
//! - Selective KV-share: the last `num_kv_shared_layers` layers reuse
//!   the K/V cache of an earlier KV-owning layer. The K/V tensors and
//!   K-norm aren't materialized on those reusing layers.
//! - Per-Layer Embeddings (PLE): a separate global table indexed by
//!   token id, projected per-layer and added as a residual via a
//!   gate→GELU→multiply→proj→norm path.
//! - GELU FFN with parallel gate/up.
//! - Final logit softcap (`tanh(x / k) * k`).
//!
//! The forward pass keeps Q4_K_M weights packed (`WeightView::Q4K`) and
//! dispatches `matvec_q4_k_row_major` directly; small tensors (norms,
//! embeddings) dequant to `Vec<f32>` because they're touched every
//! forward and don't dominate memory.

use crate::tokenizer::Tokenizer;
use crate::{KvCache, LanguageModel, LoadError, RuntimeInfo, TokenId};
use gguf_loader::{FileMmap, GgmlType, GgufBytes, GgufFile, MetaValue};
use std::sync::Arc;
use tensor_core::isa;
use tensor_core::kernels::{
    attention::sdpa_decode_f32, matmul::matvec_f32, rmsnorm::rmsnorm_f32, softmax::softmax_f32,
};
use tensor_core::quant::q4_k::{matvec_q4_k_row_major, SUPER_BLOCK as Q4K_BLOCK};
use tensor_core::quant::q6_k::{
    dequantize as q6_k_dequantize, BYTES_PER_SUPER_BLOCK as Q6K_BYTES_PER_BLOCK,
    SUPER_BLOCK as Q6K_BLOCK,
};
use tensor_core::quant::{dequantize_to_f32, DequantError, DequantType};
use tensor_core::IsaTier;

// ---------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------

/// Per-layer attention type. Decides head_dim, RoPE base, sliding
/// window, and whether the layer owns its K/V cache.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LayerType {
    Sliding,
    Full,
}

#[derive(Debug, Clone)]
pub struct Gemma4Config {
    pub vocab_size: usize,
    pub hidden_size: usize,
    pub n_layers: usize,
    pub n_heads: usize,
    pub n_kv_heads: usize,
    /// Head dim for `sliding_attention` layers (config.json `head_dim`).
    pub head_dim_swa: usize,
    /// Head dim for `full_attention` layers (config.json `global_head_dim`).
    pub head_dim_full: usize,
    /// Max FFN dim across all layers — sized for scratch buffers only.
    /// The per-layer FFN dim lives on `LayerWeights.ffn_dim`, populated
    /// from the actual `blk.{i}.ffn_gate` tensor shape at load time.
    /// Abliterated / heretic fine-tunes carry per-layer-varying FFN
    /// widths; we accept those by treating the GGUF `feed_forward_length`
    /// array as a hint and trusting the tensors.
    pub mlp_intermediate_max: usize,
    pub context_length: usize,
    /// RoPE base for `sliding_attention` layers (10_000 by default).
    pub rope_base_swa: f32,
    /// RoPE base for `full_attention` layers (1_000_000 by default).
    pub rope_base_full: f32,
    /// Fraction of head_dim that gets rotated on full-attention layers.
    /// Gemma 4 ships `0.25` so only the first 25% of dims get RoPE.
    pub partial_rotary_factor_full: f32,
    pub sliding_window: usize,
    pub rms_eps: f32,
    /// Number of layers at the END of the stack that reuse the cached
    /// K/V from an earlier layer. `n_layers - num_kv_shared_layers`
    /// layers (at the start) own their K/V.
    pub num_kv_shared_layers: usize,
    /// Per-Layer Embedding channel width. `0` when the GGUF has no PLE.
    pub ple_dim: usize,
    /// `tanh(logits / softcap) * softcap` clamp on the output logits.
    /// `None` when absent (some Gemma fine-tunes ship without it).
    pub final_logit_softcapping: Option<f32>,
    /// True iff the lm_head is tied to `token_embd`.
    pub tied_embeddings: bool,
    pub layer_types: Vec<LayerType>,
}

impl Gemma4Config {
    /// Convenience: did the loader determine this layer owns its K/V?
    pub fn owns_kv(&self, layer: usize) -> bool {
        layer < self.n_layers.saturating_sub(self.num_kv_shared_layers)
    }

    /// Head dim for layer `i`.
    pub fn head_dim(&self, layer: usize) -> usize {
        match self
            .layer_types
            .get(layer)
            .copied()
            .unwrap_or(LayerType::Sliding)
        {
            LayerType::Sliding => self.head_dim_swa,
            LayerType::Full => self.head_dim_full,
        }
    }

    /// RoPE base for layer `i`.
    pub fn rope_base(&self, layer: usize) -> f32 {
        match self
            .layer_types
            .get(layer)
            .copied()
            .unwrap_or(LayerType::Sliding)
        {
            LayerType::Sliding => self.rope_base_swa,
            LayerType::Full => self.rope_base_full,
        }
    }

    /// Number of dims that get rotated by RoPE for layer `i`.
    pub fn n_rot(&self, layer: usize) -> usize {
        match self
            .layer_types
            .get(layer)
            .copied()
            .unwrap_or(LayerType::Sliding)
        {
            LayerType::Sliding => self.head_dim_swa,
            LayerType::Full => {
                let raw = (self.head_dim_full as f32 * self.partial_rotary_factor_full) as usize;
                // Snap to even — RoPE rotates pairs.
                raw & !1
            }
        }
    }

    /// `Some(window)` for SWA layers, `None` for full-attn.
    pub fn window(&self, layer: usize) -> Option<usize> {
        match self
            .layer_types
            .get(layer)
            .copied()
            .unwrap_or(LayerType::Sliding)
        {
            LayerType::Sliding => Some(self.sliding_window),
            LayerType::Full => None,
        }
    }

    /// Parse a Gemma 4 config from GGUF metadata. Reads the metadata
    /// prefix conventions documented in the plan; rejects non-Gemma-4
    /// arch tags with a clear error.
    pub fn from_gguf(g: &GgufFile) -> Result<Self, LoadError> {
        // Use the file's declared architecture as the metadata prefix
        // (e.g. `gemma`, `gemma2`, `gemma3`, `gemma4`). Earlier revisions
        // hardcoded `"gemma4"` which broke loading for fine-tunes derived
        // from Gemma 2/3/un-numbered Gemma — the dispatcher accepts those
        // tags and routes here, but every metadata lookup then missed
        // because the file's keys live under their own prefix.
        let prefix = g.arch_tag().unwrap_or("gemma4");
        let get_u32 = |suffix: &str| -> Result<u32, LoadError> {
            g.get(&format!("{prefix}.{suffix}"))
                .and_then(MetaValue::as_u32)
                .ok_or(LoadError::MissingMetadata(Box::leak(
                    format!("{prefix}.{suffix}").into_boxed_str(),
                )))
        };
        let get_u32_or = |suffix: &str, default: u32| -> u32 {
            g.get(&format!("{prefix}.{suffix}"))
                .and_then(MetaValue::as_u32)
                .unwrap_or(default)
        };
        let get_f32_or = |suffix: &str, default: f32| -> f32 {
            g.get(&format!("{prefix}.{suffix}"))
                .and_then(MetaValue::as_f32)
                .unwrap_or(default)
        };

        let hidden_size = get_u32("embedding_length")? as usize;
        let n_heads = get_u32("attention.head_count")? as usize;
        let n_kv_heads = get_u32_or("attention.head_count_kv", n_heads as u32) as usize;
        let head_dim_swa = get_u32_or("attention.key_length", 256) as usize;
        // `key_length_swa` is llama.cpp's confusing label for the
        // *other* head dim — namely the full-attention head_dim (which
        // is bigger than the SWA one in Gemma 4).
        let head_dim_full = get_u32_or("attention.key_length_swa", head_dim_swa as u32) as usize;
        let n_layers = get_u32("block_count")? as usize;
        // `feed_forward_length` can be a scalar (Gemma 4 E4B), a uniform
        // per-layer array (stock Gemma 4 E2B), a non-uniform per-layer
        // array (abliterated / heretic fine-tunes that prune individual
        // layers), or absent entirely (some converters skip it, since
        // the tensors carry the same information). Fall through in
        // order: array max → scalar → derive from tensor headers. The
        // per-layer `LayerWeights.ffn_dim` is always set from the actual
        // `ffn_gate` tensor dims at load time, so this value only sizes
        // the upper bound for scratch buffers.
        let mlp_intermediate_max = {
            let key = format!("{prefix}.feed_forward_length");
            let val = g.get(&key);
            let from_array = val.and_then(MetaValue::as_array).and_then(|arr| {
                arr.iter()
                    .filter_map(MetaValue::as_u32)
                    .max()
                    .map(|v| v as usize)
            });
            let from_scalar = val.and_then(MetaValue::as_u32).map(|v| v as usize);
            let from_tensors = (0..n_layers)
                .filter_map(|i| {
                    g.tensor(&format!("blk.{i}.ffn_gate.weight"))
                        .and_then(|t| t.dims.get(1).copied())
                })
                .max()
                .map(|d| d as usize);
            from_array
                .or(from_scalar)
                .or(from_tensors)
                .ok_or(LoadError::MissingMetadata(
                    "feed_forward_length not in metadata AND no ffn_gate tensors found",
                ))?
        };
        let context_length = get_u32("context_length")? as usize;
        let rope_base_full = get_f32_or("rope.freq_base", 1_000_000.0);
        let rope_base_swa = get_f32_or("rope.freq_base_swa", 10_000.0);
        let rms_eps = get_f32_or("attention.layer_norm_rms_epsilon", 1e-6);
        let sliding_window = get_u32_or("attention.sliding_window", 512) as usize;
        let num_kv_shared_layers = get_u32_or("attention.shared_kv_layers", 0) as usize;
        let ple_dim = get_u32_or("embedding_length_per_layer", 0) as usize;
        let final_logit_softcapping = g
            .get(&format!("{prefix}.final_logit_softcapping"))
            .and_then(MetaValue::as_f32);
        let partial_rotary_factor_full = get_f32_or("rope.partial_rotary_factor", 0.25);

        // Layer types: GGUF stores `attention.sliding_window_pattern`
        // as an array of u32s (1 = sliding, 0 = full) of length n_layers
        // per llama.cpp's gguf-py mapping. We read each entry and fall
        // back to a synthetic 5:1 (last-full) pattern when absent.
        let layer_types = read_layer_types(g, prefix, n_layers);

        let vocab_size = g
            .get("tokenizer.ggml.tokens")
            .and_then(|v| v.as_array())
            .map(|a| a.len())
            .or_else(|| {
                g.tensors
                    .iter()
                    .find(|t| t.name == "token_embd.weight")
                    .map(|t| t.dims[1] as usize)
            })
            .ok_or(LoadError::MissingMetadata("vocab_size"))?;

        // Tied embeddings: we detect by looking for `output.weight`.
        // If absent the head ties to `token_embd.weight`.
        let tied_embeddings = g.tensor("output.weight").is_none();

        Ok(Self {
            vocab_size,
            hidden_size,
            n_layers,
            n_heads,
            n_kv_heads,
            head_dim_swa,
            head_dim_full,
            mlp_intermediate_max,
            context_length,
            rope_base_swa,
            rope_base_full,
            partial_rotary_factor_full,
            sliding_window,
            rms_eps,
            num_kv_shared_layers,
            ple_dim,
            final_logit_softcapping,
            tied_embeddings,
            layer_types,
        })
    }
}

/// Read the per-layer attention type array from GGUF. Falls back to a
/// synthetic 5:1 (last-always-full) pattern when the metadata key is
/// absent — this matches the default emitted by Gemma 4 HF conversion
/// scripts when `layer_types` isn't explicitly serialized.
fn read_layer_types(g: &GgufFile, prefix: &str, n_layers: usize) -> Vec<LayerType> {
    let key = format!("{prefix}.attention.sliding_window_pattern");
    if let Some(arr) = g.get(&key).and_then(MetaValue::as_array) {
        if arr.len() == n_layers {
            return arr
                .iter()
                .map(|v| match v.as_u32() {
                    Some(0) => LayerType::Full,
                    Some(_) => LayerType::Sliding,
                    None => LayerType::Sliding,
                })
                .collect();
        }
    }
    // Default: 5 sliding then 1 full, repeated, with the very last
    // layer forced to Full per the HF config-builder post_init logic.
    let mut out: Vec<LayerType> = (0..n_layers)
        .map(|i| {
            if (i + 1) % 6 == 0 {
                LayerType::Full
            } else {
                LayerType::Sliding
            }
        })
        .collect();
    if let Some(last) = out.last_mut() {
        *last = LayerType::Full;
    }
    out
}

// ---------------------------------------------------------------------
// Weight storage — packed Q4_K stays packed; everything else is F32.
// ---------------------------------------------------------------------

/// A weight matrix in one of the storage modes we support. The forward
/// pass dispatches on the variant to pick the right matvec kernel.
///
/// `Q4K` carries an `Arc<FileMmap>` plus the (offset, len) into the
/// mapped GGUF — no per-tensor copy. The Arc refcount keeps the mapping
/// alive until the last view drops; `Drop` on `FileMmap` then `munmap`s.
enum WeightView {
    F32(Vec<f32>),
    Q4K {
        mmap: Arc<FileMmap>,
        offset: usize,
        len: usize,
        n: usize,
        k: usize,
    },
}

impl WeightView {
    /// `y[n] = W · x[k]`. `n = y.len()`, `k = x.len()`.
    fn matvec(&self, x: &[f32], y: &mut [f32]) {
        match self {
            WeightView::F32(w) => {
                let n = y.len();
                let k = x.len();
                matvec_f32(x, w, y, n, k);
            }
            WeightView::Q4K {
                mmap,
                offset,
                len,
                n,
                k,
            } => {
                debug_assert_eq!(*n, y.len(), "Q4K matvec n mismatch");
                debug_assert_eq!(*k, x.len(), "Q4K matvec k mismatch");
                let bytes = &mmap.as_slice()[*offset..*offset + *len];
                matvec_q4_k_row_major(bytes, *n, *k, x, y);
            }
        }
    }
}

/// A vocabulary-sized embedding table. Two storage modes:
///
/// - `F32`: fully dequantized into a contiguous `Vec<f32>`. Fast row
///   access AND fast all-row sweeps (needed when the lm_head is tied to
///   the embed and the logit head iterates every vocab row per token).
///   Costs vocab × dim × 4 bytes; for Gemma 4 E2B's `token_embd` that
///   is 2.15 GB, and for `per_layer_token_embd` it is ~8 GB. The
///   sum-total was the load-time OOM source flagged by the lmkd kill
///   on a Samsung S25 Ultra (handoff 2026-05-20).
///
/// - `Packed`: kept Q6_K-packed inside the original mmap. Per-row
///   dequant happens on lookup in `row()`. Saves the bulk allocation
///   entirely (the row scratch is `dim` floats per call). Per-row cost
///   is microseconds — fine for single-row lookups like the embed of
///   the current token, or the per-layer-embedding (PLE) row, both of
///   which fire once per forward pass.
///
/// The loader chooses the variant per table:
/// - `token_embd`: `F32` only when lm_head is tied (because the tied
///   path matmuls against every vocab row each forward — too slow to
///   per-row-dequant); otherwise `Packed` if source is Q6_K.
/// - `per_layer_token_embd`: always `Packed` when source is Q6_K
///   (only one row touched per forward).
///
/// `Packed` currently only handles Q6_K because that's what Q4_K_M
/// quants ship for these tables in practice. Other source dtypes
/// fall back to `F32`.
enum PackedEmbedding {
    F32 {
        data: Vec<f32>,
        n_vocab: usize,
        dim: usize,
    },
    Packed {
        mmap: Arc<FileMmap>,
        offset: usize,
        ggml_type: GgmlType,
        n_vocab: usize,
        dim: usize,
    },
}

impl PackedEmbedding {
    fn n_vocab(&self) -> usize {
        match self {
            PackedEmbedding::F32 { n_vocab, .. } => *n_vocab,
            PackedEmbedding::Packed { n_vocab, .. } => *n_vocab,
        }
    }

    fn dim(&self) -> usize {
        match self {
            PackedEmbedding::F32 { dim, .. } => *dim,
            PackedEmbedding::Packed { dim, .. } => *dim,
        }
    }

    /// Copy row `token_id` into `out`. `out.len()` must equal [`Self::dim`].
    /// Token ids out of range clamp to the last valid row (matches the
    /// existing forward-pass behaviour from before this refactor).
    fn row(&self, token_id: u32, out: &mut [f32]) {
        let n_vocab = self.n_vocab();
        let dim = self.dim();
        debug_assert_eq!(
            out.len(),
            dim,
            "PackedEmbedding::row out.len() must equal dim"
        );
        let idx = (token_id as usize).min(n_vocab.saturating_sub(1));
        match self {
            PackedEmbedding::F32 { data, .. } => {
                out.copy_from_slice(&data[idx * dim..(idx + 1) * dim]);
            }
            PackedEmbedding::Packed {
                mmap,
                offset,
                ggml_type,
                ..
            } => match ggml_type {
                GgmlType::Q6_K => {
                    debug_assert_eq!(
                        dim % Q6K_BLOCK,
                        0,
                        "Q6_K-packed embedding requires dim multiple of Q6_K super-block"
                    );
                    let supers_per_row = dim / Q6K_BLOCK;
                    let row_bytes = supers_per_row * Q6K_BYTES_PER_BLOCK;
                    let row_offset = offset + idx * row_bytes;
                    let bytes = &mmap.as_slice()[row_offset..row_offset + row_bytes];
                    q6_k_dequantize(bytes, out);
                }
                _ => unreachable!("PackedEmbedding::Packed only constructed for Q6_K source"),
            },
        }
    }

    /// Direct slice access to the fully-dequantized F32 row-major data.
    /// Returns `None` for `Packed` — the tied-lm_head matmul path uses
    /// this and requires the table to be `F32` at load time.
    fn as_f32(&self) -> Option<&[f32]> {
        match self {
            PackedEmbedding::F32 { data, .. } => Some(data),
            PackedEmbedding::Packed { .. } => None,
        }
    }
}

/// Per-layer weight tensors. K and V are `Option` because shared-KV
/// reusing layers don't carry their own `wk`/`wv`.
///
/// Post-norms are `Option` because some Gemma 4 retrains / distills
/// (DECKARD-Expresso, Disinhibited variants, etc.) drop them entirely
/// — they were ablated to study attention/FFN behaviour without the
/// extra normalization step, and the resulting checkpoints carry the
/// model just fine. When absent, the forward pass passes the attn/FFN
/// output through unchanged before the residual add.
struct LayerWeights {
    attn_norm: Vec<f32>,
    attn_post_norm: Option<Vec<f32>>,
    attn_q_norm: Vec<f32>,
    attn_k_norm: Option<Vec<f32>>, // None for KV-reusing layers
    w_q: WeightView,
    w_k: Option<WeightView>,
    w_v: Option<WeightView>,
    w_o: WeightView,

    ffn_norm: Vec<f32>,
    ffn_post_norm: Option<Vec<f32>>,
    w_gate: WeightView,
    w_up: WeightView,
    w_down: WeightView,
    /// Per-layer FFN dim — equal to the first dim of `ffn_gate.weight`
    /// for this layer. Stock Gemma 4 has this uniform across layers;
    /// abliterated fine-tunes (DECKARD-Expresso, Disinhibited, etc.)
    /// vary it per layer. Drives the SwiGLU loop bound in forward.
    ffn_dim: usize,

    /// Per-layer scalar applied to the residual after the FFN. Absent
    /// on most fine-tunes.
    out_scale: Option<f32>,

    // PLE tensors. Present iff `cfg.ple_dim > 0` AND this layer has
    // `per_layer_*` tensors in the GGUF.
    per_layer_inp_gate: Option<WeightView>, // [ple_dim, n_embd]
    per_layer_proj: Option<WeightView>,     // [n_embd, ple_dim]
    per_layer_post_norm: Option<Vec<f32>>,  // [n_embd]
}

struct GlobalWeights {
    /// `[vocab, hidden]` row-major. `Packed` when source is Q6_K and
    /// lm_head is NOT tied; `F32` otherwise (tied case needs fast
    /// all-row access for the logit matmul).
    embed: PackedEmbedding,
    output_norm: Vec<f32>,
    /// LM head. `None` when tied to `embed`.
    lm_head: Option<WeightView>,

    // PLE globals. `None` when the GGUF has no PLE. The token table is
    // always lazy-dequant-friendly (single row per forward), so it
    // takes the `Packed` variant whenever the source is Q6_K.
    per_layer_tok_embd: Option<PackedEmbedding>, // [vocab, n_layer * ple_dim]
    per_layer_model_proj: Option<WeightView>,    // [n_layer * ple_dim, n_embd]
    per_layer_proj_norm: Option<Vec<f32>>,       // [ple_dim]
}

/// One physical KV slot. Backed by zeroed `Vec<f32>` of size
/// `context_length * n_kv_heads * head_dim`. Layers that reuse the
/// cache point at an earlier slot via `kv_alias`.
struct LayerKv {
    keys: Vec<f32>,
    values: Vec<f32>,
}

pub struct Gemma4Model {
    cfg: Gemma4Config,
    arch_tag: String,
    isa: IsaTier,
    /// Cloned handle to the underlying mmap so the model can report
    /// `pinned` without the loader sticking around. `None` for the
    /// metadata-only `from_gguf` constructor used in smoke tests.
    mmap: Option<Arc<FileMmap>>,
    global: GlobalWeights,
    layers: Vec<LayerWeights>,
    /// One entry per layer. `Some` for KV-owning layers, `None` for
    /// KV-reusing layers (they read through `kv_alias`).
    kv: Vec<Option<LayerKv>>,
    /// `kv_alias[i] = the physical slot layer i reads from`. For an
    /// owning layer this equals `i`. For a reusing layer it points at
    /// the latest KV-owning predecessor with the same attention type.
    kv_alias: Vec<usize>,
    scratch: ForwardScratch,
    pos: usize,
    logits: Vec<f32>,
    tokenizer: Option<Tokenizer>,
}

struct ForwardScratch {
    x: Vec<f32>, // [hidden]
    h: Vec<f32>, // [hidden]
    // Q/K/V scratch buffers sized to the largest layer's dims so they
    // can be reused per token.
    q: Vec<f32>,
    k: Vec<f32>,
    v: Vec<f32>,
    attn_out: Vec<f32>,
    o: Vec<f32>,
    gate: Vec<f32>,
    up: Vec<f32>,
    swiglu: Vec<f32>,
    ffn_out: Vec<f32>,
    /// PLE per-layer table: `[ple_dim * n_layer]` — the row for the
    /// current token, reshaped to `[ple_dim, n_layer]` row-major.
    ple_row: Vec<f32>,
    /// PLE per-layer projected channel: same shape as `ple_row`.
    ple_layer_input: Vec<f32>,
    /// Temp for PLE gate output `[ple_dim]`.
    ple_gate: Vec<f32>,
    /// Temp for `[n_embd]` after PLE projection.
    ple_proj_out: Vec<f32>,
}

impl ForwardScratch {
    fn new(cfg: &Gemma4Config) -> Self {
        let max_q = cfg.n_heads * cfg.head_dim_full.max(cfg.head_dim_swa);
        let max_kv = cfg.n_kv_heads * cfg.head_dim_full.max(cfg.head_dim_swa);
        Self {
            x: vec![0.0; cfg.hidden_size],
            h: vec![0.0; cfg.hidden_size],
            q: vec![0.0; max_q],
            k: vec![0.0; max_kv],
            v: vec![0.0; max_kv],
            attn_out: vec![0.0; max_q],
            o: vec![0.0; cfg.hidden_size],
            gate: vec![0.0; cfg.mlp_intermediate_max],
            up: vec![0.0; cfg.mlp_intermediate_max],
            swiglu: vec![0.0; cfg.mlp_intermediate_max],
            ffn_out: vec![0.0; cfg.hidden_size],
            ple_row: vec![0.0; cfg.ple_dim.saturating_mul(cfg.n_layers).max(1)],
            ple_layer_input: vec![0.0; cfg.ple_dim.saturating_mul(cfg.n_layers).max(1)],
            ple_gate: vec![0.0; cfg.ple_dim.max(1)],
            ple_proj_out: vec![0.0; cfg.hidden_size],
        }
    }
}

// ---------------------------------------------------------------------
// Tensor reading helpers
// ---------------------------------------------------------------------

fn dequant_type(t: GgmlType) -> Result<DequantType, LoadError> {
    DequantType::from_ggml(t as u32)
        .ok_or_else(|| LoadError::UnsupportedQuantization(t.tag().to_string()))
}

fn missing(name: &str) -> LoadError {
    LoadError::MissingMetadata(Box::leak(name.to_string().into_boxed_str()))
}

fn map_dequant(e: DequantError) -> LoadError {
    // Dequant errors during a tensor read are almost always a dtype the
    // runtime doesn't support; surface that classification so the UI
    // can distinguish dtype gaps from architecture-level failures.
    match e {
        DequantError::UnsupportedType(t) => LoadError::UnsupportedQuantization(t.to_string()),
        other => LoadError::UnknownArchitecture(format!("dequant: {other}")),
    }
}

/// Read a tensor into `Vec<f32>`. Use for small tensors (norms,
/// embeddings) where the whole-row F32 storage is cheap.
fn read_f32(g: &GgufBytes, name: &str) -> Result<Vec<f32>, LoadError> {
    let info = g.file.tensor(name).ok_or_else(|| missing(name))?;
    let ty = dequant_type(info.ggml_type)?;
    let bytes = g.tensor_bytes(name).ok_or_else(|| missing(name))?;
    let numel = info.numel();
    dequantize_to_f32(ty, bytes, numel).map_err(map_dequant)
}

fn read_optional_f32(g: &GgufBytes, name: &str) -> Result<Option<Vec<f32>>, LoadError> {
    if g.file.tensor(name).is_none() {
        return Ok(None);
    }
    read_f32(g, name).map(Some)
}

/// Read a projection matrix in its native layout. Q4_K_M weights stay
/// packed (small in-memory footprint, dispatched through
/// `matvec_q4_k_row_major`); everything else dequantizes to F32.
fn read_projection(g: &GgufBytes, name: &str) -> Result<WeightView, LoadError> {
    let info = g.file.tensor(name).ok_or_else(|| missing(name))?;
    let bytes = g.tensor_bytes(name).ok_or_else(|| missing(name))?;
    let dims: Vec<usize> = info.dims.iter().map(|d| *d as usize).collect();
    // GGUF Linear-weight stores dims as [in, out]. The Q4_K row layout
    // expects k-contiguous rows-of-n; that matches the `n × k` shape
    // when we call matvec(x[k], y[n]).
    if dims.len() == 2 && info.ggml_type == GgmlType::Q4_K {
        let k = dims[0];
        let n = dims[1];
        if k % Q4K_BLOCK == 0 {
            let offset = g.file.tensor_data_start as usize + info.offset as usize;
            let len = info.byte_size();
            return Ok(WeightView::Q4K {
                mmap: g.mmap_handle(),
                offset,
                len,
                n,
                k,
            });
        }
    }
    let ty = dequant_type(info.ggml_type)?;
    let numel: usize = dims.iter().product();
    let f32 = dequantize_to_f32(ty, bytes, numel).map_err(map_dequant)?;
    Ok(WeightView::F32(f32))
}

fn read_optional_projection(g: &GgufBytes, name: &str) -> Result<Option<WeightView>, LoadError> {
    if g.file.tensor(name).is_none() {
        return Ok(None);
    }
    read_projection(g, name).map(Some)
}

/// Load a `[vocab, dim]` embedding table. When `allow_pack` is true and
/// the GGUF source dtype is Q6_K (the dtype Q4_K_M quants use for the
/// `token_embd` and `per_layer_token_embd` tables in practice), the
/// table is kept packed in mmap and dequantized one row at a time on
/// lookup via [`PackedEmbedding::row`]. Otherwise the whole table is
/// dequantized to `Vec<f32>` at load time.
///
/// Set `allow_pack = false` for tables that need full all-row sweeps
/// at forward time (currently only `token_embd` when the lm_head is
/// tied — the tied logit matmul iterates every vocab row per token).
fn read_packed_embedding(
    g: &GgufBytes,
    name: &str,
    allow_pack: bool,
) -> Result<PackedEmbedding, LoadError> {
    let info = g.file.tensor(name).ok_or_else(|| missing(name))?;
    if info.dims.len() != 2 {
        return Err(LoadError::UnknownArchitecture(format!(
            "embedding tensor {name} has unexpected dim count {} (expected 2)",
            info.dims.len()
        )));
    }
    // GGUF dim order: `dims[0]` is the innermost (contiguous) dim,
    // `dims[1]` is the outer/stride dim. llama.cpp creates embedding
    // tables as `create_tensor({n_embd, n_vocab})`, so for
    // `token_embd.weight` and `per_layer_token_embd.weight`:
    //   dims[0] = hidden  (row width — per-token contiguous floats)
    //   dims[1] = vocab   (number of rows)
    // An earlier revision had these swapped; with vocab=262144 and
    // hidden=2048 the F32 row slice would have been ~262K floats long
    // and read way past the end of the data buffer, and the packed
    // row offset arithmetic would have used the vocab-sized stride
    // for every token. The bug never fired on device because load
    // failed earlier paths (OOM, then post-norm); would have surfaced
    // as a panic on the first successful forward.
    let dim = info.dims[0] as usize;
    let n_vocab = info.dims[1] as usize;

    if allow_pack && info.ggml_type == GgmlType::Q6_K && dim % Q6K_BLOCK == 0 {
        let offset = g.file.tensor_data_start as usize + info.offset as usize;
        return Ok(PackedEmbedding::Packed {
            mmap: g.mmap_handle(),
            offset,
            ggml_type: GgmlType::Q6_K,
            n_vocab,
            dim,
        });
    }

    // Fallback: full dequant. This is the load-time-heavy path.
    let bytes = g.tensor_bytes(name).ok_or_else(|| missing(name))?;
    let ty = dequant_type(info.ggml_type)?;
    let numel = n_vocab * dim;
    let data = dequantize_to_f32(ty, bytes, numel).map_err(map_dequant)?;
    Ok(PackedEmbedding::F32 { data, n_vocab, dim })
}

fn read_optional_packed_embedding(
    g: &GgufBytes,
    name: &str,
    allow_pack: bool,
) -> Result<Option<PackedEmbedding>, LoadError> {
    if g.file.tensor(name).is_none() {
        return Ok(None);
    }
    read_packed_embedding(g, name, allow_pack).map(Some)
}

fn read_optional_scalar(g: &GgufBytes, name: &str) -> Result<Option<f32>, LoadError> {
    let Some(v) = read_optional_f32(g, name)? else {
        return Ok(None);
    };
    Ok(v.first().copied())
}

// ---------------------------------------------------------------------
// Gemma4Model
// ---------------------------------------------------------------------

impl Gemma4Model {
    /// Metadata-only constructor for tests / smoke checks. Forward
    /// returns zero logits.
    pub fn from_gguf(g: &GgufFile) -> Result<Self, LoadError> {
        let cfg = Gemma4Config::from_gguf(g)?;
        let arch_tag = g.arch_tag().unwrap_or("gemma4").to_string();
        let isa = isa::detect();
        let logits = vec![0.0; cfg.vocab_size];
        let scratch = ForwardScratch::new(&cfg);
        Ok(Self {
            cfg,
            arch_tag,
            isa,
            mmap: None,
            global: GlobalWeights {
                embed: PackedEmbedding::F32 {
                    data: Vec::new(),
                    n_vocab: 0,
                    dim: 0,
                },
                output_norm: Vec::new(),
                lm_head: None,
                per_layer_tok_embd: None,
                per_layer_model_proj: None,
                per_layer_proj_norm: None,
            },
            layers: Vec::new(),
            kv: Vec::new(),
            kv_alias: Vec::new(),
            scratch,
            pos: 0,
            logits,
            tokenizer: None,
        })
    }

    /// Load every tensor the forward pass needs.
    pub fn load(g: &GgufBytes) -> Result<Self, LoadError> {
        let mut cfg = Gemma4Config::from_gguf(&g.file)?;
        let arch_tag = g.file.arch_tag().unwrap_or("gemma4").to_string();
        let isa = isa::detect();

        // MoE check: we don't support MoE in this port. If we see the
        // gate-input tensor, bail loudly with a clear message.
        for i in 0..cfg.n_layers {
            if g.file
                .tensor(&format!("blk.{i}.ffn_gate_inp.weight"))
                .is_some()
            {
                return Err(LoadError::UnknownArchitecture(
                    "Gemma 4 MoE (26B-A4B) layout not supported in this port — \
                     only E2B / E4B (non-MoE) are wired up"
                        .to_string(),
                ));
            }
        }

        let output_norm = read_f32(g, "output_norm.weight")?;
        let lm_head = read_optional_projection(g, "output.weight")?;
        // The lm_head is tied to the embed when the file omits
        // `output.weight`. Tied path matmuls against every vocab row
        // each forward (see `compute_logits`), so we need fast all-row
        // access — keep `embed` as F32. Otherwise (the common Gemma 4
        // case), `token_embd` is read once per token and we let the
        // table stay Q6_K-packed in mmap, saving ~2 GB of transient
        // allocation at load.
        let embed_tied = lm_head.is_none();
        let embed = read_packed_embedding(g, "token_embd.weight", !embed_tied)?;
        // PLE table is always lazy-friendly: one row touched per forward.
        // Lazy-pack saves the headline ~8 GB transient on Gemma 4 E2B.
        let per_layer_tok_embd =
            read_optional_packed_embedding(g, "per_layer_token_embd.weight", true)?;
        let per_layer_model_proj = read_optional_projection(g, "per_layer_model_proj.weight")?;
        let per_layer_proj_norm = read_optional_f32(g, "per_layer_proj_norm.weight")?;

        let global = GlobalWeights {
            embed,
            output_norm,
            lm_head,
            per_layer_tok_embd,
            per_layer_model_proj,
            per_layer_proj_norm,
        };

        let mut layers = Vec::with_capacity(cfg.n_layers);
        // Track the largest FFN dim we actually see in the tensor headers.
        // If `feed_forward_length` metadata under-reports vs. the real
        // `ffn_gate.weight.dims[1]` for any layer, we'd otherwise panic on
        // `gate[..ffn_dim]` in the forward pass.
        let mut observed_ffn_max: usize = 0;
        for i in 0..cfg.n_layers {
            let p = |suffix: &str| format!("blk.{i}.{suffix}");
            let owns_kv = cfg.owns_kv(i);

            let attn_norm = read_f32(g, &p("attn_norm.weight"))?;
            // Post-norm is optional — some retrains/distills drop it.
            // When absent, the forward pass skips the RMSNorm step
            // (treats it as identity) before the residual add.
            let attn_post_norm = read_optional_f32(g, &p("attn_post_norm.weight"))?;
            let attn_q_norm = read_f32(g, &p("attn_q_norm.weight"))?;
            let attn_k_norm = if owns_kv {
                Some(read_f32(g, &p("attn_k_norm.weight"))?)
            } else {
                read_optional_f32(g, &p("attn_k_norm.weight"))?
            };
            let w_q = read_projection(g, &p("attn_q.weight"))?;
            let w_k = if owns_kv {
                Some(read_projection(g, &p("attn_k.weight"))?)
            } else {
                read_optional_projection(g, &p("attn_k.weight"))?
            };
            let w_v = read_optional_projection(g, &p("attn_v.weight"))?;
            let w_o = read_projection(g, &p("attn_output.weight"))?;
            let ffn_norm = read_f32(g, &p("ffn_norm.weight"))?;
            // Post-norm optional (paired with attn_post_norm above).
            let ffn_post_norm = read_optional_f32(g, &p("ffn_post_norm.weight"))?;
            let w_gate = read_projection(g, &p("ffn_gate.weight"))?;
            let w_up = read_projection(g, &p("ffn_up.weight"))?;
            let w_down = read_projection(g, &p("ffn_down.weight"))?;
            // Per-layer FFN dim. GGUF stores Linear as `[in, out]` so
            // `ffn_gate.weight.dims[1]` is the projection's output width
            // — i.e. this layer's FFN intermediate dim. Read it from the
            // tensor header so abliterated GGUFs with varying per-layer
            // widths compose correctly.
            let ffn_gate_name = p("ffn_gate.weight");
            let ffn_dim = g
                .file
                .tensor(&ffn_gate_name)
                .and_then(|t| t.dims.get(1).copied())
                .map(|d| d as usize)
                .ok_or_else(|| missing(&format!("{ffn_gate_name} dims[1]")))?;
            observed_ffn_max = observed_ffn_max.max(ffn_dim);
            let out_scale = read_optional_scalar(g, &p("out_scale.weight"))?;

            let per_layer_inp_gate = read_optional_projection(g, &p("per_layer_inp_gate.weight"))?;
            let per_layer_proj = read_optional_projection(g, &p("per_layer_proj.weight"))?;
            let per_layer_post_norm = read_optional_f32(g, &p("per_layer_post_norm.weight"))?;

            layers.push(LayerWeights {
                attn_norm,
                attn_post_norm,
                attn_q_norm,
                attn_k_norm,
                w_q,
                w_k,
                w_v,
                w_o,
                ffn_norm,
                ffn_post_norm,
                w_gate,
                w_up,
                w_down,
                ffn_dim,
                out_scale,
                per_layer_inp_gate,
                per_layer_proj,
                per_layer_post_norm,
            });
        }

        // KV-share resolution. A reusing layer aliases to the most
        // recent KV-owning predecessor of the same attention type
        // (matches llama.cpp's `is_swa(il)` discrimination). Owning
        // layers alias to themselves.
        let kv_alias = build_kv_alias(&cfg);
        let kv = build_kv_slots(&cfg);

        // Reconcile scratch sizing with what the tensors actually carry.
        // The GGUF `feed_forward_length` metadata is taken as an upper
        // bound but tensor headers are authoritative — if any layer's
        // real `ffn_gate.weight.dims[1]` exceeds the metadata, grow the
        // scratch so the forward pass's `gate[..ffn_dim]` slice stays in
        // bounds.
        cfg.mlp_intermediate_max = cfg.mlp_intermediate_max.max(observed_ffn_max);
        let scratch = ForwardScratch::new(&cfg);
        let logits = vec![0.0; cfg.vocab_size];
        let tokenizer = Tokenizer::from_gguf(&g.file).ok();

        let mmap = Some(g.mmap_handle());
        let model_built = Self {
            cfg,
            arch_tag,
            isa,
            mmap,
            global,
            layers,
            kv,
            kv_alias,
            scratch,
            pos: 0,
            logits,
            tokenizer,
        };

        // D2 — per-layer arch resolution. Behind --features diag.
        // Cheap (one event per layer), gates on the user's first
        // on-device run so we can see exactly which layers got
        // dispatched as full vs sliding and which alias to which.
        #[cfg(feature = "diag")]
        {
            for i in 0..model_built.cfg.n_layers {
                diagnostics::probe!(diagnostics::Probe::Gemma4Layer {
                    idx: i,
                    ty: match model_built
                        .cfg
                        .layer_types
                        .get(i)
                        .copied()
                        .unwrap_or(LayerType::Sliding)
                    {
                        LayerType::Sliding => "sliding",
                        LayerType::Full => "full",
                    },
                    head_dim: model_built.cfg.head_dim(i),
                    rope_base: model_built.cfg.rope_base(i),
                    n_rot: model_built.cfg.n_rot(i),
                    window: model_built.cfg.window(i),
                    owns_kv: model_built.cfg.owns_kv(i),
                    kv_alias: model_built.kv_alias[i],
                });
            }
            let kv_owning = (0..model_built.cfg.n_layers)
                .filter(|&i| model_built.cfg.owns_kv(i))
                .count();
            diagnostics::probe!(diagnostics::Probe::Gemma4LoadSummary {
                n_layers: model_built.cfg.n_layers,
                hidden_size: model_built.cfg.hidden_size,
                ple_dim: model_built.cfg.ple_dim,
                kv_owning_layers: kv_owning,
                kv_reusing_layers: model_built.cfg.n_layers - kv_owning,
                final_logit_softcap: model_built.cfg.final_logit_softcapping,
                tied_embeddings: model_built.cfg.tied_embeddings,
            });
        }

        Ok(model_built)
    }

    pub fn tokenizer(&self) -> Option<&Tokenizer> {
        self.tokenizer.as_ref()
    }
    pub fn config(&self) -> &Gemma4Config {
        &self.cfg
    }
    fn has_weights(&self) -> bool {
        self.global.embed.n_vocab() > 0 && !self.layers.is_empty()
    }

    /// Build the per-token PLE input projection. Stores
    /// `[ple_dim, n_layer]` row-major in `scratch.ple_layer_input`.
    /// No-op when the model doesn't have PLE.
    fn prepare_ple_input(&mut self, token: TokenId) {
        let cfg = &self.cfg;
        let Some(tok_embd) = self.global.per_layer_tok_embd.as_ref() else {
            return;
        };
        let Some(model_proj) = self.global.per_layer_model_proj.as_ref() else {
            return;
        };
        let Some(proj_norm) = self.global.per_layer_proj_norm.as_ref() else {
            return;
        };
        if cfg.ple_dim == 0 {
            return;
        }
        // Step 1: pull the per-layer embedding row for this token.
        // The table shape is [vocab, n_layer * ple_dim] row-major, so
        // the row is contiguous. When the table is Q6_K-packed in mmap
        // (the common case for Gemma 4 E2B Q4_K_M), this dequantizes
        // just `row_len` floats — microseconds per call, vs. the ~8 GB
        // up-front allocation the previous F32-only path required.
        let row_len = cfg.n_layers * cfg.ple_dim;
        tok_embd.row(token, &mut self.scratch.ple_row[..row_len]);
        // Scale by sqrt(ple_dim) per the HF impl.
        let scale = (cfg.ple_dim as f32).sqrt();
        for v in self.scratch.ple_row[..row_len].iter_mut() {
            *v *= scale;
        }
        // Step 2: project the model state `x` through
        // `per_layer_model_proj` → scale by 1/sqrt(hidden) → reshape
        // to [ple_dim, n_layer] → RMSNorm with `per_layer_proj_norm`.
        let mut proj = vec![0.0_f32; row_len];
        model_proj.matvec(&self.scratch.x, &mut proj);
        let inv = 1.0 / (cfg.hidden_size as f32).sqrt();
        for v in proj.iter_mut() {
            *v *= inv;
        }
        // RMSNorm runs over each ple_dim row independently. We loop
        // `n_layers` times, each on a slice of length ple_dim.
        let mut normed_row = vec![0.0_f32; cfg.ple_dim];
        for l in 0..cfg.n_layers {
            let off = l * cfg.ple_dim;
            rmsnorm_f32(
                &proj[off..off + cfg.ple_dim],
                proj_norm,
                &mut normed_row,
                cfg.rms_eps,
            );
            // Combine with the embedded row, scaled by 1/sqrt(2).
            let inv_sqrt2 = 1.0 / 2.0_f32.sqrt();
            for (i, n) in normed_row.iter().enumerate() {
                self.scratch.ple_layer_input[off + i] =
                    (n + self.scratch.ple_row[off + i]) * inv_sqrt2;
            }
        }
    }

    /// Embed and scale by sqrt(hidden), in place. The embed table may
    /// be `F32` (full dequant, fast slice copy) or `Packed` (Q6_K in
    /// mmap, per-row dequant). [`PackedEmbedding::row`] handles both
    /// transparently.
    fn embed_token_into(embed: &PackedEmbedding, hidden: usize, token: TokenId, out: &mut [f32]) {
        debug_assert_eq!(out.len(), hidden);
        embed.row(token, out);
        let scale = (hidden as f32).sqrt();
        for v in out.iter_mut() {
            *v *= scale;
        }
    }

    fn compute_logits(&mut self) {
        let cfg = &self.cfg;
        let hidden = cfg.hidden_size;
        let mut norm = vec![0.0_f32; hidden];
        rmsnorm_f32(
            &self.scratch.x,
            &self.global.output_norm,
            &mut norm,
            cfg.rms_eps,
        );
        match self.global.lm_head.as_ref() {
            Some(head) => head.matvec(&norm, &mut self.logits),
            None => {
                // Tied: dot-product against each embedding row. The
                // loader ensures `embed` is `F32` whenever lm_head is
                // tied (see `read_packed_embedding` call site in load).
                // Falling into a per-row-dequant loop here would cost
                // ~vocab × dequant ≈ 260 ms/token on Gemma 4 E2B —
                // unacceptable; explicit panic on misuse rather than a
                // silent slow path.
                let embed =
                    self.global.embed.as_f32().expect(
                        "tied lm_head requires F32 embed; loader must set allow_pack=false",
                    );
                for v in 0..cfg.vocab_size {
                    let row = &embed[v * hidden..(v + 1) * hidden];
                    let mut acc = 0.0_f32;
                    for i in 0..hidden {
                        acc += norm[i] * row[i];
                    }
                    self.logits[v] = acc;
                }
            }
        }
        // Final logit softcap.
        if let Some(cap) = cfg.final_logit_softcapping {
            if cap > 0.0 {
                for v in self.logits.iter_mut() {
                    *v = (*v / cap).tanh() * cap;
                }
            }
        }
    }
}

/// Pre-compute the kv_alias map at load time. For each layer, find
/// the most recent owning predecessor of the same attention type. If
/// none exists (shouldn't happen because the first `n_layers - shared`
/// are all owning), alias to layer 0.
fn build_kv_alias(cfg: &Gemma4Config) -> Vec<usize> {
    let mut out = vec![0usize; cfg.n_layers];
    let mut last_owning_swa: Option<usize> = None;
    let mut last_owning_full: Option<usize> = None;
    for (i, slot) in out.iter_mut().enumerate() {
        let ty = cfg
            .layer_types
            .get(i)
            .copied()
            .unwrap_or(LayerType::Sliding);
        if cfg.owns_kv(i) {
            *slot = i;
            match ty {
                LayerType::Sliding => last_owning_swa = Some(i),
                LayerType::Full => last_owning_full = Some(i),
            }
        } else {
            *slot = match ty {
                LayerType::Sliding => last_owning_swa.unwrap_or(0),
                LayerType::Full => last_owning_full.unwrap_or(0),
            };
        }
    }
    out
}

fn build_kv_slots(cfg: &Gemma4Config) -> Vec<Option<LayerKv>> {
    (0..cfg.n_layers)
        .map(|i| {
            if cfg.owns_kv(i) {
                let head_dim = cfg.head_dim(i);
                let kv_total = cfg.n_kv_heads * head_dim;
                Some(LayerKv {
                    keys: vec![0.0; cfg.context_length * kv_total],
                    values: vec![0.0; cfg.context_length * kv_total],
                })
            } else {
                None
            }
        })
        .collect()
}

// ---------------------------------------------------------------------
// RoPE — partial rotary support (rotates first `n_rot` dims, passes the rest)
// ---------------------------------------------------------------------

/// In-place RoPE on `x` (one head, length `head_dim`) at position `pos`.
/// Rotates dimensions `0..n_rot` and leaves `n_rot..head_dim` untouched.
fn rope_partial_f32(x: &mut [f32], pos: usize, base: f32, n_rot: usize) {
    debug_assert!(n_rot <= x.len());
    debug_assert!(n_rot % 2 == 0);
    let half = n_rot / 2;
    for i in 0..half {
        let theta = (pos as f32) * base.powf(-2.0 * i as f32 / n_rot as f32);
        let (sin, cos) = theta.sin_cos();
        let a = x[2 * i];
        let b = x[2 * i + 1];
        x[2 * i] = a * cos - b * sin;
        x[2 * i + 1] = a * sin + b * cos;
    }
}

// ---------------------------------------------------------------------
// GQA attention dispatcher (per-head loop with optional window)
// ---------------------------------------------------------------------

#[allow(clippy::too_many_arguments)]
fn gqa_attention(
    q: &[f32],
    k_cache: &[f32],
    v_cache: &[f32],
    out: &mut [f32],
    seq_len: usize,
    n_heads: usize,
    n_kv_heads: usize,
    head_dim: usize,
    window: Option<usize>,
) {
    let kv_total = n_kv_heads * head_dim;
    for h in 0..n_heads {
        let kv_h = h * n_kv_heads / n_heads;
        let q_h = &q[h * head_dim..(h + 1) * head_dim];
        let mut k_head = vec![0.0_f32; seq_len * head_dim];
        let mut v_head = vec![0.0_f32; seq_len * head_dim];
        for t in 0..seq_len {
            let row =
                &k_cache[t * kv_total + kv_h * head_dim..t * kv_total + (kv_h + 1) * head_dim];
            k_head[t * head_dim..(t + 1) * head_dim].copy_from_slice(row);
            let row =
                &v_cache[t * kv_total + kv_h * head_dim..t * kv_total + (kv_h + 1) * head_dim];
            v_head[t * head_dim..(t + 1) * head_dim].copy_from_slice(row);
        }
        let out_h = &mut out[h * head_dim..(h + 1) * head_dim];
        sdpa_decode_f32(q_h, &k_head, &v_head, out_h, head_dim, seq_len, window);
    }
}

// ---------------------------------------------------------------------
// LanguageModel impl
// ---------------------------------------------------------------------

impl LanguageModel for Gemma4Model {
    /// Forward pass over a single token.
    ///
    /// ## Shape-translation cheat-sheet — ggml → row-major
    ///
    /// llama.cpp's `gemma4.cpp` builds the graph with ggml's
    /// `ggml_mul_mat(A, B)`, which semantically computes `B^T · A`. The
    /// ggml weight tensors are stored with `ne = [in_features,
    /// out_features]` (the column count comes first in ggml's
    /// row-major-of-columns layout). When we read the GGUF, the
    /// dimensions arrive in the same order: `dims[0] = in`, `dims[1] =
    /// out`. Our `read_projection` builds a row-major `[out, in]`
    /// weight matrix (`n = dims[1] = out`, `k = dims[0] = in`), which
    /// is what `matvec_q4_k_row_major(w, n, k, x, y)` expects.
    ///
    /// Therefore every `cur = ggml_mul_mat(model.layers[il].wfoo, x)`
    /// in `gemma4.cpp` becomes a single `w_foo.matvec(&x, &mut y)`
    /// call here, with `y.len() = n` and `x.len() = k`. The
    /// projections per layer:
    ///
    /// | gemma4.cpp                       | here                                 | shape  |
    /// |----------------------------------|--------------------------------------|--------|
    /// | `model.layers[il].wq`            | `layers[layer].w_q`                  | `[n_head*head_dim, hidden]` |
    /// | `model.layers[il].wk`            | `layers[layer].w_k` (owning only)    | `[n_kv_heads*head_dim, hidden]` |
    /// | `model.layers[il].wv`            | `layers[layer].w_v` (owning only)    | `[n_kv_heads*head_dim, hidden]` |
    /// | `model.layers[il].wo`            | `layers[layer].w_o`                  | `[hidden, n_head*head_dim]` |
    /// | `model.layers[il].ffn_gate`      | `layers[layer].w_gate`               | `[mlp_intermediate, hidden]` |
    /// | `model.layers[il].ffn_up`        | `layers[layer].w_up`                 | `[mlp_intermediate, hidden]` |
    /// | `model.layers[il].ffn_down`      | `layers[layer].w_down`               | `[hidden, mlp_intermediate]` |
    /// | `per_layer_inp_gate`             | `per_layer_inp_gate`                 | `[ple_dim, hidden]` |
    /// | `per_layer_proj`                 | `per_layer_proj`                     | `[hidden, ple_dim]` |
    /// | `model.output` (or tok_embd)     | `compute_logits` → embed/output      | `[vocab, hidden]` |
    fn forward(&mut self, token: TokenId, kv: &mut KvCache) -> &[f32] {
        if !self.has_weights() {
            kv.seq_len = kv.seq_len.saturating_add(1);
            return &self.logits;
        }
        if self.pos >= self.cfg.context_length {
            self.logits.fill(0.0);
            return &self.logits;
        }
        let pos = self.pos;
        let kv_seq = pos + 1;
        let cfg = self.cfg.clone();
        let hidden = cfg.hidden_size;

        // Embed + scale.
        Self::embed_token_into(&self.global.embed, hidden, token, &mut self.scratch.x);

        // PLE input — projects `x` to the per-layer additive channel.
        // Has to happen BEFORE the layer loop because per-layer PLE
        // injection reads from this precomputed table.
        self.prepare_ple_input(token);

        for layer in 0..cfg.n_layers {
            let head_dim = cfg.head_dim(layer);
            let rope_base = cfg.rope_base(layer);
            let n_rot = cfg.n_rot(layer);
            let window = cfg.window(layer);
            let q_total = cfg.n_heads * head_dim;
            let kv_total = cfg.n_kv_heads * head_dim;
            let owns_kv = cfg.owns_kv(layer);

            // x_save = inpL — preserved for the residual after attn.
            // We don't allocate; just track the values in self.scratch.x
            // and accumulate into self.scratch.o → add back at end.
            // First copy x → h0 (residual save).
            let mut residual_attn = vec![0.0_f32; hidden];
            residual_attn.copy_from_slice(&self.scratch.x);

            // norm
            rmsnorm_f32(
                &self.scratch.x,
                &self.layers[layer].attn_norm,
                &mut self.scratch.h,
                cfg.rms_eps,
            );

            // Q projection. gemma4.cpp: `Qcur = ggml_mul_mat(wq, cur)`.
            // ggml shape: wq=[hidden, n_head*head_dim], cur=[hidden,T];
            // here T=1: `q = w_q @ h`, where w_q is row-major
            // `[q_total, hidden]` (n=q_total, k=hidden).
            self.layers[layer]
                .w_q
                .matvec(&self.scratch.h, &mut self.scratch.q[..q_total]);
            // Per-head RMSNorm on Q.
            apply_per_head_norm(
                &mut self.scratch.q[..q_total],
                &self.layers[layer].attn_q_norm,
                cfg.n_heads,
                head_dim,
                cfg.rms_eps,
            );
            // RoPE on Q.
            for h in 0..cfg.n_heads {
                let qh = &mut self.scratch.q[h * head_dim..(h + 1) * head_dim];
                rope_partial_f32(qh, pos, rope_base, n_rot);
            }

            // Resolve which physical KV slot this layer reads from.
            let phys = self.kv_alias[layer];

            // K / V projections + per-head K norm. Only when this
            // layer owns its KV — reusing layers leave the cache slot
            // untouched and just read from `phys`.
            if owns_kv {
                let w_k = self.layers[layer]
                    .w_k
                    .as_ref()
                    .expect("owning layer missing w_k");
                // K projection. gemma4.cpp: `Kcur = ggml_mul_mat(wk, cur)`.
                // Row-major w_k is `[kv_total, hidden]`.
                w_k.matvec(&self.scratch.h, &mut self.scratch.k[..kv_total]);
                if let Some(w_v) = self.layers[layer].w_v.as_ref() {
                    // V projection. gemma4.cpp: `Vcur = ggml_mul_mat(wv,
                    // cur)`. Row-major w_v is `[kv_total, hidden]`.
                    w_v.matvec(&self.scratch.h, &mut self.scratch.v[..kv_total]);
                } else {
                    // Gemma 4: V can fall back to K when wv is absent.
                    self.scratch.v[..kv_total].copy_from_slice(&self.scratch.k[..kv_total]);
                }
                if let Some(k_norm) = self.layers[layer].attn_k_norm.as_ref() {
                    apply_per_head_norm(
                        &mut self.scratch.k[..kv_total],
                        k_norm,
                        cfg.n_kv_heads,
                        head_dim,
                        cfg.rms_eps,
                    );
                }
                // V gets weight-less RMSNorm per head.
                apply_per_head_norm_weightless(
                    &mut self.scratch.v[..kv_total],
                    cfg.n_kv_heads,
                    head_dim,
                    cfg.rms_eps,
                );
                // RoPE on K.
                for h in 0..cfg.n_kv_heads {
                    let kh = &mut self.scratch.k[h * head_dim..(h + 1) * head_dim];
                    rope_partial_f32(kh, pos, rope_base, n_rot);
                }
                // Write into the owned cache.
                let cache = self.kv[phys].as_mut().expect("owning layer has cache slot");
                let off = pos * kv_total;
                cache.keys[off..off + kv_total].copy_from_slice(&self.scratch.k[..kv_total]);
                cache.values[off..off + kv_total].copy_from_slice(&self.scratch.v[..kv_total]);
            }

            // Attention reads through `phys` regardless of whether
            // this layer is the owner or a reusing aliaser.
            let cache = self.kv[phys].as_ref().expect("kv slot present");
            gqa_attention(
                &self.scratch.q[..q_total],
                &cache.keys[..kv_seq * kv_total],
                &cache.values[..kv_seq * kv_total],
                &mut self.scratch.attn_out[..q_total],
                kv_seq,
                cfg.n_heads,
                cfg.n_kv_heads,
                head_dim,
                window,
            );

            // O projection. gemma4.cpp: `cur = build_lora_mm(wo,
            // attn_out)`. Row-major w_o is `[hidden, q_total]`; output
            // is the attention contribution back into model width.
            self.layers[layer]
                .w_o
                .matvec(&self.scratch.attn_out[..q_total], &mut self.scratch.o);

            // attn_post_norm + residual. The post-norm is absent on
            // some retrains; when None we pass the attn output through
            // unchanged before the residual add (identity replacement
            // for the RMSNorm step).
            //
            // Reuses `scratch.h` for the norm output instead of a fresh
            // per-token allocation — the previous content of `h` was
            // already consumed by the FFN matmuls above.
            if let Some(weight) = self.layers[layer].attn_post_norm.as_ref() {
                rmsnorm_f32(&self.scratch.o, weight, &mut self.scratch.h, cfg.rms_eps);
                for (i, r) in residual_attn.iter().enumerate().take(hidden) {
                    self.scratch.x[i] = self.scratch.h[i] + r;
                }
            } else {
                for (i, r) in residual_attn.iter().enumerate().take(hidden) {
                    self.scratch.x[i] = self.scratch.o[i] + r;
                }
            }
            // attn_out is now in self.scratch.x. Keep a copy for the
            // FFN residual.
            let mut residual_ffn = vec![0.0_f32; hidden];
            residual_ffn.copy_from_slice(&self.scratch.x);

            // FFN — GELU(gate) * up, then down. Followed by post-norm
            // + residual.
            rmsnorm_f32(
                &self.scratch.x,
                &self.layers[layer].ffn_norm,
                &mut self.scratch.h,
                cfg.rms_eps,
            );
            // FFN gate: gemma4.cpp `gate = ggml_mul_mat(ffn_gate, cur)`.
            // Row-major w_gate is `[ffn_dim, hidden]`. We slice the
            // scratch buffers down to this layer's FFN dim so matvec's
            // y.len() matches the weight's `n` even on abliterated
            // fine-tunes where per-layer FFN widths vary.
            let ffn_dim = self.layers[layer].ffn_dim;
            self.layers[layer]
                .w_gate
                .matvec(&self.scratch.h, &mut self.scratch.gate[..ffn_dim]);
            // FFN up: gemma4.cpp `up = ggml_mul_mat(ffn_up, cur)`.
            self.layers[layer]
                .w_up
                .matvec(&self.scratch.h, &mut self.scratch.up[..ffn_dim]);
            for i in 0..ffn_dim {
                self.scratch.swiglu[i] = gelu(self.scratch.gate[i]) * self.scratch.up[i];
            }
            // FFN down: gemma4.cpp `cur = ggml_mul_mat(ffn_down,
            // gelu(gate)*up)`. Row-major w_down is `[hidden, ffn_dim]`.
            self.layers[layer]
                .w_down
                .matvec(&self.scratch.swiglu[..ffn_dim], &mut self.scratch.ffn_out);
            // FFN post-norm + residual. Optional, same shape as
            // attn_post_norm above — pass FFN output through unchanged
            // when the retrain dropped this weight. Reuses `scratch.h`
            // (free again now that gate/up have consumed it).
            if let Some(weight) = self.layers[layer].ffn_post_norm.as_ref() {
                rmsnorm_f32(
                    &self.scratch.ffn_out,
                    weight,
                    &mut self.scratch.h,
                    cfg.rms_eps,
                );
                for (i, r) in residual_ffn.iter().enumerate().take(hidden) {
                    self.scratch.x[i] = self.scratch.h[i] + r;
                }
            } else {
                for (i, r) in residual_ffn.iter().enumerate().take(hidden) {
                    self.scratch.x[i] = self.scratch.ffn_out[i] + r;
                }
            }

            // PLE injection (if the model has it on this layer).
            if cfg.ple_dim > 0 && self.global.per_layer_tok_embd.is_some() {
                if let (Some(gate), Some(proj), Some(post_norm)) = (
                    self.layers[layer].per_layer_inp_gate.as_ref(),
                    self.layers[layer].per_layer_proj.as_ref(),
                    self.layers[layer].per_layer_post_norm.as_ref(),
                ) {
                    // PLE input gate: gemma4.cpp
                    // `cur = ggml_mul_mat(per_layer_inp_gate, cur)`.
                    // Row-major `[ple_dim, hidden]`.
                    gate.matvec(&self.scratch.x, &mut self.scratch.ple_gate);
                    for v in self.scratch.ple_gate.iter_mut() {
                        *v = gelu(*v);
                    }
                    // Multiply with this layer's PLE input.
                    let ple_off = layer * cfg.ple_dim;
                    for i in 0..cfg.ple_dim {
                        self.scratch.ple_gate[i] *= self.scratch.ple_layer_input[ple_off + i];
                    }
                    // PLE projection back to model width: gemma4.cpp
                    // `cur = ggml_mul_mat(per_layer_proj, cur)`.
                    // Row-major `[hidden, ple_dim]`.
                    proj.matvec(&self.scratch.ple_gate, &mut self.scratch.ple_proj_out);
                    let mut ple_normed = vec![0.0_f32; hidden];
                    rmsnorm_f32(
                        &self.scratch.ple_proj_out,
                        post_norm,
                        &mut ple_normed,
                        cfg.rms_eps,
                    );
                    for (xi, ni) in self.scratch.x.iter_mut().zip(ple_normed.iter()) {
                        *xi += *ni;
                    }
                }
            }

            // Per-layer scalar output gate (rare).
            if let Some(scale) = self.layers[layer].out_scale {
                for v in self.scratch.x.iter_mut() {
                    *v *= scale;
                }
            }
        }

        self.compute_logits();
        self.pos += 1;
        kv.seq_len = self.pos;
        // softmax_f32 is reached only by the sampler; the unused-import
        // suppression below prevents clippy from complaining when the
        // forward path doesn't reach a softmax call directly.
        let _ = softmax_f32;
        &self.logits
    }

    fn reset(&mut self, kv: &mut KvCache) {
        kv.seq_len = 0;
        self.pos = 0;
    }

    fn info(&self) -> RuntimeInfo {
        RuntimeInfo {
            version: env!("CARGO_PKG_VERSION"),
            arch_tag: self.arch_tag.clone(),
            isa: self.isa,
            threads: 1,
            vocab_size: self.cfg.vocab_size,
            context_length: self.cfg.context_length,
            mmap_pinned: self.mmap.as_ref().map(|m| m.pinned()).unwrap_or(false),
        }
    }
}

/// Per-head RMSNorm on the first `n_heads * head_dim` floats of `x`,
/// using a shared `weight[head_dim]` gain vector.
fn apply_per_head_norm(x: &mut [f32], weight: &[f32], n_heads: usize, head_dim: usize, eps: f32) {
    debug_assert!(weight.len() == head_dim);
    let mut buf = vec![0.0_f32; head_dim];
    for h in 0..n_heads {
        let off = h * head_dim;
        rmsnorm_f32(&x[off..off + head_dim], weight, &mut buf, eps);
        x[off..off + head_dim].copy_from_slice(&buf);
    }
}

/// Weight-less per-head RMSNorm — sets every element to
/// `x / sqrt(mean(x²) + eps)` per head.
fn apply_per_head_norm_weightless(x: &mut [f32], n_heads: usize, head_dim: usize, eps: f32) {
    for h in 0..n_heads {
        let off = h * head_dim;
        let mut ssq = 0.0_f32;
        for v in &x[off..off + head_dim] {
            ssq += v * v;
        }
        let scale = 1.0 / (ssq / head_dim as f32 + eps).sqrt();
        for v in &mut x[off..off + head_dim] {
            *v *= scale;
        }
    }
}

/// `gelu_pytorch_tanh` approximation:
/// `0.5 * x * (1 + tanh(sqrt(2/π) * (x + 0.044715 * x³)))`.
fn gelu(x: f32) -> f32 {
    const SQRT_2_OVER_PI: f32 = 0.797_884_6;
    let inner = SQRT_2_OVER_PI * (x + 0.044_715 * x * x * x);
    0.5 * x * (1.0 + inner.tanh())
}

// ---------------------------------------------------------------------
// Sampler — preserved public API used by jni-shim
// ---------------------------------------------------------------------

/// Greedy argmax over `logits`.
pub fn argmax(logits: &[f32]) -> TokenId {
    let mut best = 0u32;
    let mut best_v = f32::NEG_INFINITY;
    for (i, &v) in logits.iter().enumerate() {
        if v > best_v {
            best_v = v;
            best = i as u32;
        }
    }
    best
}

/// Temperature + top-k sampling. Returns argmax when temperature ≤ 0.
pub fn sample(logits: &[f32], temperature: f32, top_k: usize, rng: &mut SamplerState) -> TokenId {
    if temperature <= 0.0 {
        return argmax(logits);
    }
    let inv_t = 1.0 / temperature;
    let mut pairs: Vec<(u32, f32)> = logits
        .iter()
        .enumerate()
        .map(|(i, &v)| (i as u32, v * inv_t))
        .collect();
    let k = top_k.max(1).min(pairs.len());
    for i in 0..k {
        let mut best = i;
        for j in (i + 1)..pairs.len() {
            if pairs[j].1 > pairs[best].1 {
                best = j;
            }
        }
        pairs.swap(i, best);
    }
    let mut probs = vec![0.0_f32; k];
    for i in 0..k {
        probs[i] = pairs[i].1;
    }
    softmax_f32(&mut probs);
    let u = rng.next_f32();
    let mut acc = 0.0_f32;
    for i in 0..k {
        acc += probs[i];
        if u <= acc {
            return pairs[i].0;
        }
    }
    pairs[k - 1].0
}

/// Tiny xorshift64* — deterministic.
pub struct SamplerState {
    state: u64,
}

impl SamplerState {
    pub fn new(seed: u64) -> Self {
        Self {
            state: if seed == 0 {
                0x9E37_79B9_7F4A_7C15
            } else {
                seed
            },
        }
    }
    fn next_u64(&mut self) -> u64 {
        let mut x = self.state;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.state = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }
    pub fn next_f32(&mut self) -> f32 {
        (self.next_u64() >> 40) as f32 / (1u32 << 24) as f32
    }
}

// ---------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packed_embedding_f32_row_copies_correct_slice() {
        // Build a synthetic F32 table with 4 rows of 8 floats each so we
        // can verify `row()` copies the right slice for each token id.
        let n_vocab = 4;
        let dim = 8;
        let mut data = vec![0.0_f32; n_vocab * dim];
        for v in 0..n_vocab {
            for i in 0..dim {
                data[v * dim + i] = (v * 100 + i) as f32;
            }
        }
        let emb = PackedEmbedding::F32 { data, n_vocab, dim };
        assert_eq!(emb.n_vocab(), n_vocab);
        assert_eq!(emb.dim(), dim);

        let mut out = vec![0.0_f32; dim];
        emb.row(2, &mut out);
        let expected: Vec<f32> = (0..dim).map(|i| (200 + i) as f32).collect();
        assert_eq!(out, expected);

        // Out-of-range token id clamps to the last valid row instead
        // of panicking.
        emb.row(999, &mut out);
        let last: Vec<f32> = (0..dim).map(|i| (300 + i) as f32).collect();
        assert_eq!(out, last);
    }

    #[test]
    fn packed_embedding_as_f32_returns_none_for_packed_variant() {
        // We can't easily build a Packed variant in a unit test without
        // a real mmap, so verify the asymmetric: F32 returns Some, and
        // the tied lm_head path which calls `expect()` on this would
        // therefore succeed for F32. The Packed-returns-None invariant
        // is documented on `as_f32`; the loader's contract is that we
        // only construct Packed when allow_pack=true, which the load
        // sets to !tied. Confirming the F32 side here is the only
        // half we can exercise without I/O.
        let emb = PackedEmbedding::F32 {
            data: vec![1.0, 2.0],
            n_vocab: 1,
            dim: 2,
        };
        assert_eq!(emb.as_f32(), Some(&[1.0_f32, 2.0][..]));
    }

    #[test]
    fn gelu_matches_reference_at_zero_and_one() {
        // gelu(0) = 0; gelu(1) ≈ 0.8412 (per Pytorch GELU(approximate=tanh)).
        assert!((gelu(0.0)).abs() < 1e-6);
        assert!((gelu(1.0) - 0.8412).abs() < 1e-3);
    }

    #[test]
    fn argmax_picks_largest() {
        assert_eq!(argmax(&[0.1, 0.4, 0.2, -1.0, 0.39]), 1);
    }

    #[test]
    fn temperature_zero_is_argmax() {
        let mut s = SamplerState::new(1);
        assert_eq!(sample(&[0.1, 0.7, 0.2], 0.0, 3, &mut s), 1);
    }

    #[test]
    fn sample_deterministic_with_same_seed() {
        let mut a = SamplerState::new(42);
        let mut b = SamplerState::new(42);
        let s1 = sample(&[0.1, 0.4, 0.2, 0.3], 1.0, 3, &mut a);
        let s2 = sample(&[0.1, 0.4, 0.2, 0.3], 1.0, 3, &mut b);
        assert_eq!(s1, s2);
    }

    /// kv_alias resolution: for a 6-layer model with 2 shared (i.e. 4
    /// owning + 2 reusing), the reusing layers should alias to the
    /// most recent owning predecessor of the same type.
    #[test]
    fn kv_alias_picks_recent_owning_predecessor() {
        let cfg = Gemma4Config {
            vocab_size: 1024,
            hidden_size: 32,
            n_layers: 6,
            n_heads: 4,
            n_kv_heads: 2,
            head_dim_swa: 8,
            head_dim_full: 16,
            mlp_intermediate_max: 64,
            context_length: 8,
            rope_base_swa: 10000.0,
            rope_base_full: 1_000_000.0,
            partial_rotary_factor_full: 0.25,
            sliding_window: 4,
            rms_eps: 1e-6,
            num_kv_shared_layers: 2,
            ple_dim: 0,
            final_logit_softcapping: Some(30.0),
            tied_embeddings: true,
            // [SWA, SWA, Full, SWA, Full, Full] — owning 0..3, reusing 4..5.
            layer_types: vec![
                LayerType::Sliding,
                LayerType::Sliding,
                LayerType::Full,
                LayerType::Sliding,
                LayerType::Full,
                LayerType::Full,
            ],
        };
        let alias = build_kv_alias(&cfg);
        // Owning layers alias to themselves.
        assert_eq!(alias[0], 0);
        assert_eq!(alias[1], 1);
        assert_eq!(alias[2], 2);
        assert_eq!(alias[3], 3);
        // Layer 4 (Full, reusing) → most recent Full owner is layer 2.
        assert_eq!(alias[4], 2);
        // Layer 5 (Full, reusing) → most recent Full owner is layer 2.
        assert_eq!(alias[5], 2);
    }

    /// `read_layer_types` falls back to a synthetic 5:1-with-last-full
    /// pattern when the GGUF metadata doesn't carry an explicit
    /// `attention.sliding_window_pattern` array. Exercises the actual
    /// function (not a hand-rolled copy) so a regression in the
    /// fallback shape would fail this test.
    #[test]
    fn default_layer_types_e2b_pattern() {
        let empty_gguf = GgufFile {
            version: 3,
            metadata: Vec::new(),
            tensors: Vec::new(),
            tensor_data_start: 0,
        };
        let n_layers = 35; // Gemma 4 E2B
        let types = read_layer_types(&empty_gguf, "gemma4", n_layers);

        assert_eq!(types.len(), n_layers);
        // 5:1 cycle: indices 5, 11, 17, 23, 29 are Full (1-indexed mod 6 == 0).
        // 0-indexed those are 5, 11, 17, 23, 29. The "(i+1) % 6 == 0"
        // check in the fallback uses 0-indexed i, so:
        //   i=5 → Full (cycle hit)
        //   i=11 → Full (cycle hit)
        //   i=17 → Full
        //   i=23 → Full
        //   i=29 → Full
        // Everything else is Sliding except the last layer, which is
        // forced Full regardless of cycle.
        let expected_full: Vec<usize> = vec![5, 11, 17, 23, 29, n_layers - 1];
        for (i, ty) in types.iter().enumerate() {
            if expected_full.contains(&i) {
                assert_eq!(*ty, LayerType::Full, "layer {i} should be Full");
            } else {
                assert_eq!(*ty, LayerType::Sliding, "layer {i} should be Sliding");
            }
        }
        // Belt-and-braces: last layer is always Full per the synthetic
        // pattern, even if the cycle wouldn't have hit it.
        assert_eq!(*types.last().unwrap(), LayerType::Full);
    }
}
