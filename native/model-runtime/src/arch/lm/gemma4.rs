//! Gemma 4 (Gemma 3n) architecture. Config-driven from GGUF metadata —
//! head count, head dim, RoPE base, KV-shared layer count, and PLE
//! dim all come from the file.
//!
//! Weight storage avoids dequantizing the large projection matrices at
//! load time. We keep the raw Q4_K_M bytes packed and call
//! [`tensor_core::quant::q4_k::matvec_q4_k_row_major`] in the forward
//! path. Norms + embeddings stay as F32 because they're small and
//! every forward pass touches them in full.
//!
//! What's wired:
//!
//! - SwiGLU decoder block (RMSNorm → Q/K/V → RoPE → GQA SDPA → O →
//!   optional Gemma 2/3 post-attn norm → residual → FFN → optional
//!   post-FFN norm → residual).
//! - KV-shared early layers (the "selective activation" of E2B/E4B).
//!   Layers `0..kv_shared_layers` alias the layer-0 KV cache on read,
//!   and skip the write so the cache is canonical for that range.
//! - PLE additive injection. If `per_layer_token_embd.weight` is
//!   present in the GGUF, we read the row for the current token and
//!   add it to the residual stream after each block.
//!
//! What ISN'T wired (intentional — the architecture extras need
//! reference parity to land correctly):
//!
//! - **AltUp** (multi-input residual streams). E2B uses 4 parallel
//!   residuals that interact via a learned router; we collapse them
//!   to one.
//! - **LAuReL** (Learned Augmented Residual Layer). Replaces the plain
//!   residual add with a gated combination of L and R projections.
//!
//! Translation: the forward pass produces token ids, the math is the
//! "vanilla SwiGLU decoder" lower bound. For coherent E2B/E4B output,
//! AltUp + LAuReL still need implementing. See the README's "Known
//! gaps" section.

use crate::tokenizer::Tokenizer;
use crate::{KvCache, LanguageModel, LoadError, RuntimeInfo, TokenId};
use gguf_loader::{GgmlType, GgufBytes, GgufFile, MetaValue};
use tensor_core::isa;
use tensor_core::kernels::{
    attention::sdpa_decode_f32, matmul::matvec_f32, rmsnorm::rmsnorm_f32, rope::rope_inplace_f32,
    softmax::softmax_f32, swiglu::swiglu_f32,
};
use tensor_core::quant::q4_k::{matvec_q4_k_row_major, SUPER_BLOCK as Q4K_BLOCK};
use tensor_core::quant::{dequantize_to_f32, DequantError, DequantType};
use tensor_core::IsaTier;

#[derive(Debug, Clone)]
pub struct Gemma4Config {
    pub vocab_size: usize,
    pub hidden_size: usize,
    pub n_layers: usize,
    pub n_heads: usize,
    pub n_kv_heads: usize,
    pub head_dim: usize,
    pub mlp_intermediate: usize,
    pub context_length: usize,
    pub rope_base: f32,
    pub sliding_window: Option<usize>,
    pub rms_eps: f32,
    pub scale_embed: bool,
    pub post_norm: bool,
    /// Gemma 4 E2B/E4B share KV cache across the first N layers.
    /// `0` for Gemma 2/3 (no sharing).
    pub kv_shared_layers: usize,
    /// Width of the Per-Layer Embedding channel. `0` when the GGUF
    /// has no PLE tensors.
    pub ple_dim: usize,
}

impl Gemma4Config {
    pub fn from_gguf(g: &GgufFile) -> Result<Self, LoadError> {
        let prefix = ["gemma4", "gemma-4", "gemma3n", "gemma3", "gemma2", "gemma"]
            .iter()
            .find(|p| g.get(&format!("{p}.context_length")).is_some())
            .copied()
            .unwrap_or("gemma");
        let get_u32 = |suffix: &str| -> Result<u32, LoadError> {
            g.get(&format!("{prefix}.{suffix}"))
                .and_then(MetaValue::as_u32)
                .ok_or(LoadError::MissingMetadata("gemma.<suffix>"))
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
        let head_dim = g
            .get(&format!("{prefix}.attention.key_length"))
            .and_then(MetaValue::as_u32)
            .map(|v| v as usize)
            .unwrap_or(hidden_size / n_heads.max(1));
        let n_layers = get_u32("block_count")? as usize;
        let mlp_intermediate = get_u32("feed_forward_length")? as usize;
        let context_length = get_u32("context_length")? as usize;
        let rope_base = get_f32_or("rope.freq_base", 10_000.0);
        let rms_eps = get_f32_or("attention.layer_norm_rms_epsilon", 1e-6);
        let sliding_window = g
            .get(&format!("{prefix}.attention.sliding_window"))
            .and_then(MetaValue::as_u32)
            .map(|v| v as usize);
        let kv_shared_layers =
            get_u32_or("attention.shared_kv_layers", 0) as usize;
        let ple_dim = get_u32_or("embedding_length_per_layer_input", 0) as usize;

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

        Ok(Self {
            vocab_size,
            hidden_size,
            n_layers,
            n_heads,
            n_kv_heads,
            head_dim,
            mlp_intermediate,
            context_length,
            rope_base,
            sliding_window,
            rms_eps,
            scale_embed: true,
            post_norm: !matches!(prefix, "gemma"),
            kv_shared_layers,
            ple_dim,
        })
    }
}

/// A weight matrix in one of the storage modes we support. The forward
/// pass dispatches on the variant to pick the right matvec kernel.
///
/// `F32` is the catch-all that handles small tensors (norms,
/// embeddings) and any quant variant we haven't specialised yet — the
/// loader still dequantizes them to F32 on disk to keep correctness.
/// `Q4K` is the big projection-matrix path; weights stay packed so the
/// in-memory footprint matches the file.
enum WeightView {
    F32(Vec<f32>),
    Q4K {
        bytes: Vec<u8>,
        n: usize,
        k: usize,
    },
}

impl WeightView {
    /// `y[n] = W · x[k]`. Shape of `W` is `[n, k]`. Caller supplies
    /// the F32 fallback path; Q4_K dispatches to the packed kernel.
    fn matvec(&self, x: &[f32], y: &mut [f32]) {
        match self {
            WeightView::F32(w) => {
                let n = y.len();
                let k = x.len();
                // matvec_f32 expects W[n, k] row-major.
                matvec_f32(x, w, y, n, k);
            }
            WeightView::Q4K { bytes, n, k } => {
                matvec_q4_k_row_major(bytes, *n, *k, x, y);
            }
        }
    }
}

/// Owning storage for one decoder layer.
struct LayerWeights {
    attn_norm: Vec<f32>,
    post_attn_norm: Option<Vec<f32>>,
    ffn_norm: Vec<f32>,
    post_ffn_norm: Option<Vec<f32>>,
    w_q: WeightView,
    w_k: WeightView,
    w_v: WeightView,
    w_o: WeightView,
    w_gate: WeightView,
    w_up: WeightView,
    w_down: WeightView,
}

struct GlobalWeights {
    embed: Vec<f32>,       // [vocab, hidden] — row-major
    output_norm: Vec<f32>, // [hidden]
    /// `[vocab, hidden]` when not tied. Falls back to `embed` at logits time.
    lm_head: Option<WeightView>,
    /// Optional `[vocab, n_layers, ple_dim]` table. When present the
    /// forward path reads `[current_token_id, layer_idx, :]` and adds
    /// it (via `w_repair`, when also present) to the residual.
    ple_table: Option<Vec<f32>>,
}

struct LayerKv {
    keys: Vec<f32>,
    values: Vec<f32>,
}

pub struct Gemma4Model {
    cfg: Gemma4Config,
    arch_tag: String,
    isa: IsaTier,
    global: GlobalWeights,
    layers: Vec<LayerWeights>,
    /// One slot per layer, but for `i < kv_shared_layers` the slot
    /// points at the layer-0 cache via [`Self::physical_layer`].
    kv: Vec<LayerKv>,
    scratch: ForwardScratch,
    pos: usize,
    logits: Vec<f32>,
    tokenizer: Option<Tokenizer>,
}

struct ForwardScratch {
    x: Vec<f32>,
    h: Vec<f32>,
    q: Vec<f32>,
    k: Vec<f32>,
    v: Vec<f32>,
    attn_out: Vec<f32>,
    o: Vec<f32>,
    gate: Vec<f32>,
    up: Vec<f32>,
    swiglu: Vec<f32>,
    ffn_out: Vec<f32>,
}

impl ForwardScratch {
    fn new(cfg: &Gemma4Config) -> Self {
        let q_total = cfg.n_heads * cfg.head_dim;
        let kv_total = cfg.n_kv_heads * cfg.head_dim;
        Self {
            x: vec![0.0; cfg.hidden_size],
            h: vec![0.0; cfg.hidden_size],
            q: vec![0.0; q_total],
            k: vec![0.0; kv_total],
            v: vec![0.0; kv_total],
            attn_out: vec![0.0; q_total],
            o: vec![0.0; cfg.hidden_size],
            gate: vec![0.0; cfg.mlp_intermediate],
            up: vec![0.0; cfg.mlp_intermediate],
            swiglu: vec![0.0; cfg.mlp_intermediate],
            ffn_out: vec![0.0; cfg.hidden_size],
        }
    }
}

fn dequant_type(t: GgmlType) -> Result<DequantType, LoadError> {
    DequantType::from_ggml(t as u32).ok_or(LoadError::UnknownArchitecture(format!(
        "unsupported tensor dtype: {}",
        t.tag()
    )))
}

/// Load a projection matrix in its native layout. Q4_K_M weights stay
/// packed (small in-memory footprint, dispatch through
/// [`matvec_q4_k_row_major`]); everything else dequantizes to F32.
fn read_projection(g: &GgufBytes, name: &str) -> Result<WeightView, LoadError> {
    let info = g
        .file
        .tensor(name)
        .ok_or_else(|| LoadError::MissingMetadata(Box::leak(name.to_string().into_boxed_str())))?;
    let bytes = g
        .tensor_bytes(name)
        .ok_or(LoadError::MissingMetadata("tensor bytes"))?;
    let dims: Vec<usize> = info.dims.iter().map(|d| *d as usize).collect();
    // GGUF stores Linear weights as [in, out] little-endian dim order
    // — `dims[0]` is the inner (k), `dims[1]` is the outer (n). The
    // packed Q4_K kernel expects rows of length k laid out per output.
    if dims.len() == 2 && info.ggml_type == GgmlType::Q4_K {
        let k = dims[0];
        let n = dims[1];
        if k % Q4K_BLOCK == 0 {
            return Ok(WeightView::Q4K {
                bytes: bytes.to_vec(),
                n,
                k,
            });
        }
    }
    // Fallback: dequantize to F32. Small tensors land here naturally.
    let ty = dequant_type(info.ggml_type)?;
    let numel: usize = dims.iter().product();
    let f32 = dequantize_to_f32(ty, bytes, numel).map_err(map_dequant)?;
    Ok(WeightView::F32(f32))
}

fn read_tensor(g: &GgufBytes, name: &str) -> Result<Vec<f32>, LoadError> {
    let info = g
        .file
        .tensor(name)
        .ok_or_else(|| LoadError::MissingMetadata(Box::leak(name.to_string().into_boxed_str())))?;
    let ty = dequant_type(info.ggml_type)?;
    let bytes = g
        .tensor_bytes(name)
        .ok_or(LoadError::MissingMetadata("tensor bytes"))?;
    let numel = info.numel();
    dequantize_to_f32(ty, bytes, numel).map_err(map_dequant)
}

fn read_optional_f32(g: &GgufBytes, name: &str) -> Result<Option<Vec<f32>>, LoadError> {
    if g.file.tensor(name).is_none() {
        return Ok(None);
    }
    read_tensor(g, name).map(Some)
}

fn read_first_f32(
    g: &GgufBytes,
    candidates: &[&str],
) -> Result<Option<Vec<f32>>, LoadError> {
    for name in candidates {
        if let Some(v) = read_optional_f32(g, name)? {
            return Ok(Some(v));
        }
    }
    Ok(None)
}

fn map_dequant(e: DequantError) -> LoadError {
    LoadError::UnknownArchitecture(format!("dequant: {e}"))
}

impl Gemma4Model {
    pub fn from_gguf(g: &GgufFile) -> Result<Self, LoadError> {
        let cfg = Gemma4Config::from_gguf(g)?;
        let arch_tag = g.arch_tag().unwrap_or("gemma").to_string();
        let isa = isa::detect();
        let logits = vec![0.0; cfg.vocab_size];
        let scratch = ForwardScratch::new(&cfg);
        Ok(Self {
            cfg,
            arch_tag,
            isa,
            global: GlobalWeights {
                embed: Vec::new(),
                output_norm: Vec::new(),
                lm_head: None,
                ple_table: None,
            },
            layers: Vec::new(),
            kv: Vec::new(),
            scratch,
            pos: 0,
            logits,
            tokenizer: None,
        })
    }

    pub fn load(g: &GgufBytes) -> Result<Self, LoadError> {
        let cfg = Gemma4Config::from_gguf(&g.file)?;
        let arch_tag = g.file.arch_tag().unwrap_or("gemma").to_string();
        let isa = isa::detect();

        let embed = read_tensor(g, "token_embd.weight")?;
        let output_norm = read_tensor(g, "output_norm.weight")?;
        let lm_head = if g.file.tensor("output.weight").is_some() {
            Some(read_projection(g, "output.weight")?)
        } else {
            None
        };
        // PLE table. The Gemma 4 GGUF convention names this
        // `per_layer_token_embd.weight`; older drafts used
        // `model.ple_embeddings`. Falls back to None when absent (Gemma
        // 2/3) — the inject path becomes a no-op.
        let ple_table = read_first_f32(
            g,
            &["per_layer_token_embd.weight", "model.ple_embeddings"],
        )?;
        let global = GlobalWeights {
            embed,
            output_norm,
            lm_head,
            ple_table,
        };

        let mut layers = Vec::with_capacity(cfg.n_layers);
        for i in 0..cfg.n_layers {
            let p = |suffix: &str| format!("blk.{i}.{suffix}");
            let attn_norm = read_tensor(g, &p("attn_norm.weight"))?;
            let post_attn_norm = if cfg.post_norm {
                read_optional_f32(g, &p("post_attention_norm.weight"))?
            } else {
                None
            };
            let ffn_norm = read_tensor(g, &p("ffn_norm.weight"))?;
            let post_ffn_norm = if cfg.post_norm {
                read_first_f32(
                    g,
                    &[&p("post_ffw_norm.weight"), &p("post_ffn_norm.weight")],
                )?
            } else {
                None
            };
            let w_q = read_projection(g, &p("attn_q.weight"))?;
            let w_k = read_projection(g, &p("attn_k.weight"))?;
            let w_v = read_projection(g, &p("attn_v.weight"))?;
            let w_o = read_projection(g, &p("attn_output.weight"))?;
            let w_gate = read_projection(g, &p("ffn_gate.weight"))?;
            let w_up = read_projection(g, &p("ffn_up.weight"))?;
            let w_down = read_projection(g, &p("ffn_down.weight"))?;
            layers.push(LayerWeights {
                attn_norm,
                post_attn_norm,
                ffn_norm,
                post_ffn_norm,
                w_q,
                w_k,
                w_v,
                w_o,
                w_gate,
                w_up,
                w_down,
            });
        }

        // KV cache: physically owned only by layer 0 for the first
        // `kv_shared_layers` (E2B/E4B) — the shared layers' slots are
        // empty Vecs to make that explicit.
        let kv_total = cfg.n_kv_heads * cfg.head_dim;
        let shared = cfg.kv_shared_layers.min(cfg.n_layers);
        let kv = (0..cfg.n_layers)
            .map(|i| {
                if i > 0 && i < shared {
                    LayerKv {
                        keys: Vec::new(),
                        values: Vec::new(),
                    }
                } else {
                    LayerKv {
                        keys: vec![0.0; cfg.context_length * kv_total],
                        values: vec![0.0; cfg.context_length * kv_total],
                    }
                }
            })
            .collect();

        let scratch = ForwardScratch::new(&cfg);
        let logits = vec![0.0; cfg.vocab_size];

        let tokenizer = Tokenizer::from_gguf(&g.file).ok();

        Ok(Self {
            cfg,
            arch_tag,
            isa,
            global,
            layers,
            kv,
            scratch,
            pos: 0,
            logits,
            tokenizer,
        })
    }

    pub fn tokenizer(&self) -> Option<&Tokenizer> {
        self.tokenizer.as_ref()
    }
    pub fn config(&self) -> &Gemma4Config {
        &self.cfg
    }

    fn has_weights(&self) -> bool {
        !self.global.embed.is_empty() && !self.layers.is_empty()
    }

    /// Map a layer index to the physical KV slot. Shared layers all
    /// resolve to slot 0; everything else maps to itself.
    fn physical_layer(&self, layer: usize) -> usize {
        if layer < self.cfg.kv_shared_layers {
            0
        } else {
            layer
        }
    }

    fn embed_token_into(
        embed: &[f32],
        hidden: usize,
        vocab: usize,
        scale_embed: bool,
        token: TokenId,
        out: &mut [f32],
    ) {
        let idx = (token as usize).min(vocab.saturating_sub(1));
        let row = &embed[idx * hidden..(idx + 1) * hidden];
        out.copy_from_slice(row);
        if scale_embed {
            let scale = (hidden as f32).sqrt();
            for v in out.iter_mut() {
                *v *= scale;
            }
        }
    }

    /// Read the PLE row for `(token_id, layer_idx)` and add it to the
    /// residual. Width compatibility: the PLE row is `ple_dim` long;
    /// we broadcast/repeat across `hidden` to land an additive
    /// contribution. Without a learned `w_repair` matrix this is the
    /// most honest minimal injection — when AltUp/LAuReL implement
    /// the real path it'll replace this.
    fn ple_inject(&self, token: TokenId, layer: usize, x: &mut [f32]) {
        let Some(table) = self.global.ple_table.as_ref() else {
            return;
        };
        let ple_dim = self.cfg.ple_dim;
        if ple_dim == 0 {
            return;
        }
        let hidden = self.cfg.hidden_size;
        let row_off =
            (token as usize * self.cfg.n_layers + layer) * ple_dim;
        if row_off + ple_dim > table.len() {
            return;
        }
        let row = &table[row_off..row_off + ple_dim];
        // Distribute the PLE contribution across the hidden vector
        // by tiling — the real Gemma 4 path projects through
        // `per_layer_proj.weight` which we don't load yet.
        for h in 0..hidden {
            x[h] += row[h % ple_dim];
        }
    }

    fn compute_logits(&mut self) {
        let hidden = self.cfg.hidden_size;
        let mut norm = vec![0.0f32; hidden];
        rmsnorm_f32(
            &self.scratch.x,
            &self.global.output_norm,
            &mut norm,
            self.cfg.rms_eps,
        );
        // Tied embedding fallback: dot-product against the embed table.
        match self.global.lm_head.as_ref() {
            Some(head) => head.matvec(&norm, &mut self.logits),
            None => {
                let embed = &self.global.embed;
                for v in 0..self.cfg.vocab_size {
                    let row = &embed[v * hidden..(v + 1) * hidden];
                    let mut acc = 0.0_f32;
                    for i in 0..hidden {
                        acc += norm[i] * row[i];
                    }
                    self.logits[v] = acc;
                }
            }
        }
    }
}

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
        let mut k_head = vec![0.0f32; seq_len * head_dim];
        let mut v_head = vec![0.0f32; seq_len * head_dim];
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

impl LanguageModel for Gemma4Model {
    fn forward(&mut self, token: TokenId, kv: &mut KvCache) -> &[f32] {
        if !self.has_weights() {
            kv.seq_len = kv.seq_len.saturating_add(1);
            return &self.logits;
        }
        if self.pos >= self.cfg.context_length {
            self.logits.fill(0.0);
            return &self.logits;
        }
        let hidden = self.cfg.hidden_size;
        let inter = self.cfg.mlp_intermediate;
        let q_total = self.cfg.n_heads * self.cfg.head_dim;
        let kv_total = self.cfg.n_kv_heads * self.cfg.head_dim;
        let head_dim = self.cfg.head_dim;

        Self::embed_token_into(
            &self.global.embed,
            hidden,
            self.cfg.vocab_size,
            self.cfg.scale_embed,
            token,
            &mut self.scratch.x,
        );
        let pos = self.pos;
        let kv_seq = pos + 1;

        for layer in 0..self.cfg.n_layers {
            let cfg = &self.cfg;
            rmsnorm_f32(
                &self.scratch.x,
                &self.layers[layer].attn_norm,
                &mut self.scratch.h,
                cfg.rms_eps,
            );

            self.layers[layer].w_q.matvec(&self.scratch.h, &mut self.scratch.q);
            self.layers[layer].w_k.matvec(&self.scratch.h, &mut self.scratch.k);
            self.layers[layer].w_v.matvec(&self.scratch.h, &mut self.scratch.v);

            for h in 0..cfg.n_heads {
                let qh = &mut self.scratch.q[h * head_dim..(h + 1) * head_dim];
                rope_inplace_f32(qh, pos, cfg.rope_base);
            }
            for h in 0..cfg.n_kv_heads {
                let kh = &mut self.scratch.k[h * head_dim..(h + 1) * head_dim];
                rope_inplace_f32(kh, pos, cfg.rope_base);
            }

            // KV append. Shared-layer writes route to physical slot 0;
            // non-shared layers own their own slot.
            let phys = self.physical_layer(layer);
            // Layers > 0 in the shared range skip the write — only
            // layer 0 populates the canonical cache.
            let do_write = phys == layer || layer == 0;
            if do_write {
                let cache = &mut self.kv[phys];
                let off = pos * kv_total;
                cache.keys[off..off + kv_total].copy_from_slice(&self.scratch.k);
                cache.values[off..off + kv_total].copy_from_slice(&self.scratch.v);
            }

            let window = cfg.sliding_window;
            let cache = &self.kv[phys];
            gqa_attention(
                &self.scratch.q,
                &cache.keys[..kv_seq * kv_total],
                &cache.values[..kv_seq * kv_total],
                &mut self.scratch.attn_out,
                kv_seq,
                cfg.n_heads,
                cfg.n_kv_heads,
                head_dim,
                window,
            );

            self.layers[layer]
                .w_o
                .matvec(&self.scratch.attn_out, &mut self.scratch.o);

            if let Some(post) = self.layers[layer].post_attn_norm.as_ref() {
                let mut tmp = vec![0.0f32; hidden];
                rmsnorm_f32(&self.scratch.o, post, &mut tmp, cfg.rms_eps);
                for (xi, ti) in self.scratch.x.iter_mut().zip(tmp.iter()) {
                    *xi += *ti;
                }
            } else {
                for (xi, oi) in self.scratch.x.iter_mut().zip(self.scratch.o.iter()) {
                    *xi += *oi;
                }
            }

            rmsnorm_f32(
                &self.scratch.x,
                &self.layers[layer].ffn_norm,
                &mut self.scratch.h,
                cfg.rms_eps,
            );
            self.layers[layer].w_gate.matvec(&self.scratch.h, &mut self.scratch.gate);
            self.layers[layer].w_up.matvec(&self.scratch.h, &mut self.scratch.up);
            swiglu_f32(
                &self.scratch.gate,
                &self.scratch.up,
                &mut self.scratch.swiglu,
            );
            self.layers[layer]
                .w_down
                .matvec(&self.scratch.swiglu, &mut self.scratch.ffn_out);

            if let Some(post) = self.layers[layer].post_ffn_norm.as_ref() {
                let mut tmp = vec![0.0f32; hidden];
                rmsnorm_f32(&self.scratch.ffn_out, post, &mut tmp, cfg.rms_eps);
                for (xi, ti) in self.scratch.x.iter_mut().zip(tmp.iter()) {
                    *xi += *ti;
                }
            } else {
                for (xi, fi) in self.scratch.x.iter_mut().zip(self.scratch.ffn_out.iter()) {
                    *xi += *fi;
                }
            }

            // PLE injection after each block. No-op when ple_table is
            // absent / ple_dim is 0.
            self.ple_inject(token, layer, &mut self.scratch.x);
        }

        self.compute_logits();
        self.pos += 1;
        kv.seq_len = self.pos;
        // softmax_f32 is referenced by the kernels module but we
        // don't apply it here — the sampler runs softmax over the
        // logits when temperature > 0.
        let _ = softmax_f32::<>;
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
        }
    }
}

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
    let mut probs = vec![0.0f32; k];
    for i in 0..k {
        probs[i] = pairs[i].1;
    }
    softmax_f32(&mut probs);
    let u = rng.next_f32();
    let mut acc = 0.0f32;
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
                0x9E3779B97F4A7C15
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
        x.wrapping_mul(0x2545F4914F6CDD1D)
    }
    pub fn next_f32(&mut self) -> f32 {
        (self.next_u64() >> 40) as f32 / (1u32 << 24) as f32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn argmax_picks_largest() {
        let logits = [0.1, 0.4, 0.2, -1.0, 0.39];
        assert_eq!(argmax(&logits), 1);
    }

    #[test]
    fn sample_deterministic_with_same_seed() {
        let logits = [0.1, 0.4, 0.2, 0.3];
        let mut a = SamplerState::new(42);
        let mut b = SamplerState::new(42);
        let s1 = sample(&logits, 1.0, 3, &mut a);
        let s2 = sample(&logits, 1.0, 3, &mut b);
        assert_eq!(s1, s2);
    }

    #[test]
    fn temperature_zero_is_argmax() {
        let logits = [0.1, 0.7, 0.2];
        let mut s = SamplerState::new(1);
        assert_eq!(sample(&logits, 0.0, 3, &mut s), 1);
    }
}
