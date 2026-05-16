//! Gemma 2/3/4 architecture. Config-driven from GGUF metadata — head
//! count, head dim, sliding window, RoPE base, etc. all come from the
//! file so the runtime never guesses. Weights are dequantized from the
//! GGUF tensor payload at load time (Q4_K / Q8_0 / Q4_0 / F16 / F32 / BF16)
//! and stored as `Vec<f32>`. The forward pass walks the standard SwiGLU
//! decoder stack using the `tensor_core::kernels` reference math.
//!
//! Memory note: a 2B model dequantized to F32 needs ~8 GB. Phones run
//! out before then. For real-world use we still need quantized matvec
//! kernels — that's the follow-up. This file produces honest tokens for
//! F16 / F32 / Q8_0 weights and small (e.g. 270M) Q4_K Gemmas that fit
//! into device RAM dequantized.

use crate::tokenizer::Tokenizer;
use crate::{KvCache, LanguageModel, LoadError, RuntimeInfo, TokenId};
use gguf_loader::{GgmlType, GgufBytes, GgufFile, MetaValue};
use tensor_core::isa;
use tensor_core::kernels::{
    attention::sdpa_decode_f32, matmul::matvec_f32, rmsnorm::rmsnorm_f32, rope::rope_inplace_f32,
    softmax::softmax_f32, swiglu::swiglu_f32,
};
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
    /// Some Gemma checkpoints multiply the embedding by sqrt(hidden_size)
    /// after lookup. The HF reference does this; we honour it when the
    /// metadata flag is set (or by default for the gemma family).
    pub scale_embed: bool,
    /// Gemma 2 / 3 add a second RMSNorm after attention and after MLP,
    /// applied to the sublayer output before the residual.
    pub post_norm: bool,
}

impl Gemma4Config {
    pub fn from_gguf(g: &GgufFile) -> Result<Self, LoadError> {
        let prefix = ["gemma4", "gemma-4", "gemma3", "gemma2", "gemma"]
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
            // Gemma 2/3/4 ship with the double-norm pattern. The flag
            // lives here so callers can flip it if a future variant
            // drops the post-attn / post-ffn norms.
            post_norm: prefix != "gemma", // gemma 1 used a single norm pair
        })
    }
}

/// Owning buffers for one decoder layer. F32 storage — the production
/// path will swap each `Vec<f32>` for a typed view that does the matvec
/// directly against quantized bytes.
struct LayerWeights {
    attn_norm: Vec<f32>,
    post_attn_norm: Option<Vec<f32>>,
    ffn_norm: Vec<f32>,
    post_ffn_norm: Option<Vec<f32>>,
    w_q: Vec<f32>,    // [n_heads * head_dim, hidden]
    w_k: Vec<f32>,    // [n_kv_heads * head_dim, hidden]
    w_v: Vec<f32>,    // [n_kv_heads * head_dim, hidden]
    w_o: Vec<f32>,    // [hidden, n_heads * head_dim]
    w_gate: Vec<f32>, // [inter, hidden]
    w_up: Vec<f32>,   // [inter, hidden]
    w_down: Vec<f32>, // [hidden, inter]
}

/// Top-level (non-per-layer) weights.
struct GlobalWeights {
    embed: Vec<f32>,           // [vocab, hidden] — row-major
    output_norm: Vec<f32>,     // [hidden]
    lm_head: Option<Vec<f32>>, // [vocab, hidden] when not tied to embed
}

/// One layer's persistent KV cache (concatenated per head, contiguous).
struct LayerKv {
    /// `[max_seq, n_kv_heads * head_dim]`. Stored as a flat Vec for
    /// pointer-friendly slicing into individual head rows.
    keys: Vec<f32>,
    values: Vec<f32>,
}

pub struct Gemma4Model {
    cfg: Gemma4Config,
    arch_tag: String,
    isa: IsaTier,
    global: GlobalWeights,
    layers: Vec<LayerWeights>,
    /// Per-layer KV cache. Indexed by `(layer, position, kv_head, dim)`.
    kv: Vec<LayerKv>,
    /// Reusable scratch buffers — avoids reallocating per token.
    scratch: ForwardScratch,
    /// Token position in the current session. Reset when the caller asks.
    pos: usize,
    logits: Vec<f32>,
    /// Optional tokenizer, kept here so callers that don't need to
    /// build one out of band can reach it via [`tokenizer`].
    tokenizer: Option<Tokenizer>,
}

struct ForwardScratch {
    x: Vec<f32>,        // [hidden]
    h: Vec<f32>,        // [hidden]
    q: Vec<f32>,        // [n_heads * head_dim]
    k: Vec<f32>,        // [n_kv_heads * head_dim]
    v: Vec<f32>,        // [n_kv_heads * head_dim]
    attn_out: Vec<f32>, // [n_heads * head_dim]
    o: Vec<f32>,        // [hidden]
    gate: Vec<f32>,     // [inter]
    up: Vec<f32>,       // [inter]
    swiglu: Vec<f32>,   // [inter]
    ffn_out: Vec<f32>,  // [hidden]
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

fn read_optional(g: &GgufBytes, name: &str) -> Result<Option<Vec<f32>>, LoadError> {
    if g.file.tensor(name).is_none() {
        return Ok(None);
    }
    read_tensor(g, name).map(Some)
}

fn map_dequant(e: DequantError) -> LoadError {
    LoadError::UnknownArchitecture(format!("dequant: {e}"))
}

impl Gemma4Model {
    /// Backwards-compatible: parses metadata only, no weight binding.
    /// Used by the model-runtime smoke test and any caller that just
    /// wants to see whether the GGUF file is structurally Gemma.
    pub fn from_gguf(g: &GgufFile) -> Result<Self, LoadError> {
        let cfg = Gemma4Config::from_gguf(g)?;
        let arch_tag = g.arch_tag().unwrap_or("gemma").to_string();
        let isa = isa::detect();
        let logits = vec![0.0; cfg.vocab_size];
        let scratch = ForwardScratch::new(&cfg);
        let global = GlobalWeights {
            embed: Vec::new(),
            output_norm: Vec::new(),
            lm_head: None,
        };
        let layers = Vec::new();
        let kv = Vec::new();
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
            tokenizer: None,
        })
    }

    /// Load the whole model — config, tokenizer, and every weight tensor
    /// the forward pass needs. Returns a model ready to call `forward`.
    pub fn load(g: &GgufBytes) -> Result<Self, LoadError> {
        let cfg = Gemma4Config::from_gguf(&g.file)?;
        let arch_tag = g.file.arch_tag().unwrap_or("gemma").to_string();
        let isa = isa::detect();

        // Global tensors. The exact key set is the GGUF convention used
        // by llama.cpp: `token_embd.weight`, `output_norm.weight`,
        // `output.weight` (optional, tied embeddings drop it).
        let embed = read_tensor(g, "token_embd.weight")?;
        let output_norm = read_tensor(g, "output_norm.weight")?;
        let lm_head = read_optional(g, "output.weight")?;
        let global = GlobalWeights {
            embed,
            output_norm,
            lm_head,
        };

        // Per-layer tensors.
        let mut layers = Vec::with_capacity(cfg.n_layers);
        for i in 0..cfg.n_layers {
            let p = |suffix: &str| format!("blk.{i}.{suffix}");
            let attn_norm = read_tensor(g, &p("attn_norm.weight"))?;
            let post_attn_norm = if cfg.post_norm {
                read_optional(g, &p("post_attention_norm.weight"))?
            } else {
                None
            };
            let ffn_norm = read_tensor(g, &p("ffn_norm.weight"))?;
            let post_ffn_norm = if cfg.post_norm {
                read_optional(g, &p("post_ffw_norm.weight"))?
                    .or(read_optional(g, &p("post_ffn_norm.weight"))?)
            } else {
                None
            };
            let w_q = read_tensor(g, &p("attn_q.weight"))?;
            let w_k = read_tensor(g, &p("attn_k.weight"))?;
            let w_v = read_tensor(g, &p("attn_v.weight"))?;
            let w_o = read_tensor(g, &p("attn_output.weight"))?;
            let w_gate = read_tensor(g, &p("ffn_gate.weight"))?;
            let w_up = read_tensor(g, &p("ffn_up.weight"))?;
            let w_down = read_tensor(g, &p("ffn_down.weight"))?;
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

        let kv_total = cfg.n_kv_heads * cfg.head_dim;
        let kv = (0..cfg.n_layers)
            .map(|_| LayerKv {
                keys: vec![0.0; cfg.context_length * kv_total],
                values: vec![0.0; cfg.context_length * kv_total],
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

    /// True iff the model was loaded with real weights (vs the
    /// metadata-only `from_gguf` path).
    fn has_weights(&self) -> bool {
        !self.global.embed.is_empty() && !self.layers.is_empty()
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

    /// Compute the output logits given the current pos's residual stream.
    /// Final-layer RMSNorm in place, then matvec with the lm_head (or
    /// the tied embedding matrix when lm_head is absent).
    fn compute_logits(&mut self) {
        let hidden = self.cfg.hidden_size;
        let mut norm = vec![0.0f32; hidden];
        rmsnorm_f32(
            &self.scratch.x,
            &self.global.output_norm,
            &mut norm,
            self.cfg.rms_eps,
        );
        let head = self.global.lm_head.as_ref().unwrap_or(&self.global.embed);
        matvec_f32(&norm, head, &mut self.logits, self.cfg.vocab_size, hidden);
    }
}

/// SDPA wrapper for grouped-query attention. The query has `n_heads`,
/// the cache has `n_kv_heads`; query head `h` reads kv-head
/// `h * n_kv_heads / n_heads`.
#[allow(clippy::too_many_arguments)] // single internal helper; collapsing this into a struct would obscure the shapes
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
        // Each KV head spans seq_len rows, each row laid out [n_kv_heads * head_dim].
        // For head kv_h: pick the [kv_h*head_dim..(kv_h+1)*head_dim] slice in every row.
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
        // The model maintains its own per-layer KV cache; the caller's
        // KvCache only tracks position so the agent loop sees a real
        // counter. Without weights we still bump the position so cancel
        // / reset works, but the logits stay zero.
        if !self.has_weights() {
            kv.seq_len = kv.seq_len.saturating_add(1);
            return &self.logits;
        }
        if self.pos >= self.cfg.context_length {
            // Out of context — return a flat distribution so the sampler
            // sees no signal and the caller can detect "stop".
            self.logits.fill(0.0);
            return &self.logits;
        }
        let hidden = self.cfg.hidden_size;
        let inter = self.cfg.mlp_intermediate;
        let q_total = self.cfg.n_heads * self.cfg.head_dim;
        let kv_total = self.cfg.n_kv_heads * self.cfg.head_dim;
        let head_dim = self.cfg.head_dim;

        // x = embed(token) * sqrt(hidden) (Gemma)
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
            // h = rmsnorm(x, attn_norm)
            rmsnorm_f32(
                &self.scratch.x,
                &self.layers[layer].attn_norm,
                &mut self.scratch.h,
                cfg.rms_eps,
            );

            // Q, K, V projections.
            matvec_f32(
                &self.scratch.h,
                &self.layers[layer].w_q,
                &mut self.scratch.q,
                q_total,
                hidden,
            );
            matvec_f32(
                &self.scratch.h,
                &self.layers[layer].w_k,
                &mut self.scratch.k,
                kv_total,
                hidden,
            );
            matvec_f32(
                &self.scratch.h,
                &self.layers[layer].w_v,
                &mut self.scratch.v,
                kv_total,
                hidden,
            );

            // RoPE on Q (per-head) and K (per-head).
            for h in 0..cfg.n_heads {
                let qh = &mut self.scratch.q[h * head_dim..(h + 1) * head_dim];
                rope_inplace_f32(qh, pos, cfg.rope_base);
            }
            for h in 0..cfg.n_kv_heads {
                let kh = &mut self.scratch.k[h * head_dim..(h + 1) * head_dim];
                rope_inplace_f32(kh, pos, cfg.rope_base);
            }

            // Append to per-layer KV cache.
            {
                let cache = &mut self.kv[layer];
                let off = pos * kv_total;
                cache.keys[off..off + kv_total].copy_from_slice(&self.scratch.k);
                cache.values[off..off + kv_total].copy_from_slice(&self.scratch.v);
            }

            // Grouped attention over keys[..kv_seq], values[..kv_seq].
            // Gemma 3 alternates a sliding window across odd layers; we
            // honour the metadata-declared window on all of them, which
            // matches the conservative "every layer windowed" reading.
            let window = cfg.sliding_window;
            let cache = &self.kv[layer];
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

            // O projection.
            matvec_f32(
                &self.scratch.attn_out,
                &self.layers[layer].w_o,
                &mut self.scratch.o,
                hidden,
                q_total,
            );

            // Gemma 2/3 post-attention norm, applied to the sublayer
            // output before the residual add.
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

            // FFN sublayer.
            rmsnorm_f32(
                &self.scratch.x,
                &self.layers[layer].ffn_norm,
                &mut self.scratch.h,
                cfg.rms_eps,
            );
            matvec_f32(
                &self.scratch.h,
                &self.layers[layer].w_gate,
                &mut self.scratch.gate,
                inter,
                hidden,
            );
            matvec_f32(
                &self.scratch.h,
                &self.layers[layer].w_up,
                &mut self.scratch.up,
                inter,
                hidden,
            );
            swiglu_f32(
                &self.scratch.gate,
                &self.scratch.up,
                &mut self.scratch.swiglu,
            );
            matvec_f32(
                &self.scratch.swiglu,
                &self.layers[layer].w_down,
                &mut self.scratch.ffn_out,
                hidden,
                inter,
            );

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
        }

        self.compute_logits();
        self.pos += 1;
        kv.seq_len = self.pos;
        &self.logits
    }

    fn reset(&mut self, kv: &mut KvCache) {
        kv.seq_len = 0;
        self.pos = 0;
        // KV cache slots are reused; no need to zero them — `kv_seq` in
        // forward() bounds the read window so stale data past `pos` is
        // never visible.
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

/// Greedy argmax over `logits` — picks the most likely next token.
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

/// Temperature + top-k sampling. `seed` advances per call via a tiny
/// xorshift so repeated runs with the same seed reproduce. Returns
/// `argmax` when `temperature` is zero.
pub fn sample(logits: &[f32], temperature: f32, top_k: usize, rng: &mut SamplerState) -> TokenId {
    if temperature <= 0.0 {
        return argmax(logits);
    }
    let inv_t = 1.0 / temperature;
    // top_k by partial sort: collect (id, logit), keep largest k.
    let mut pairs: Vec<(u32, f32)> = logits
        .iter()
        .enumerate()
        .map(|(i, &v)| (i as u32, v * inv_t))
        .collect();
    let k = top_k.max(1).min(pairs.len());
    // Partial selection: largest k via repeated swap of the max into pos i.
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

/// Tiny xorshift64* — deterministic, no allocations, no dependencies.
pub struct SamplerState {
    state: u64,
}

impl SamplerState {
    pub fn new(seed: u64) -> Self {
        Self {
            state: if seed == 0 { 0x9E3779B97F4A7C15 } else { seed },
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
        // 24 high bits → [0, 1)
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
