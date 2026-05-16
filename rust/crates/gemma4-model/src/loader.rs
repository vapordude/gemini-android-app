//! Load Gemma 4 weights from a `model.safetensors` file. Maps the HF
//! naming convention to the layer-buffer shapes [`LayerWeights`] expects.
//!
//! Real Gemma checkpoints ship in BF16. The reader pulls bytes through
//! [`gemma4_core::SafeTensors::as_f32`], which dequantizes BF16 / F16 /
//! F32 transparently. INT8 / Q4_K_M paths live behind their own crates
//! and aren't bound here yet.
//!
//! HF stores `Linear.weight` in `[out, in]` row-major order. The matmul
//! kernels in [`gemma4_ops::matmul_f32`] expect `[in, out]`, so every
//! projection matrix is transposed at load time.

use crate::config::Gemma4Config;
use crate::weights::{GlobalWeights, LayerWeights};
use gemma4_core::SafeTensors;

#[derive(Debug)]
pub enum LoadError {
    Parse(&'static str),
    Missing(String),
    UnsupportedDtype(String),
    ShapeMismatch(String),
}

impl core::fmt::Display for LoadError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            LoadError::Parse(s) => write!(f, "parse: {s}"),
            LoadError::Missing(s) => write!(f, "missing tensor: {s}"),
            LoadError::UnsupportedDtype(s) => {
                write!(f, "unsupported dtype for {s} (try BF16/F16/F32)")
            }
            LoadError::ShapeMismatch(s) => write!(f, "shape mismatch: {s}"),
        }
    }
}

/// Read the non-layer tensors: embeddings, final RMSNorm gain, optional
/// LM head (None when embeddings are tied per `cfg.tied_embeddings`).
pub fn load_global(st: &SafeTensors, cfg: &Gemma4Config) -> Result<GlobalWeights, LoadError> {
    let embed = read_or(st, "model.embed_tokens.weight")?;
    let norm_final = read_or(st, "model.norm.weight")?;
    let lm_head = if cfg.tied_embeddings {
        None
    } else {
        // Some HF checkpoints export the head as either `lm_head.weight`
        // (Gemma 1/2) or `output.weight`. Try both before giving up.
        read_optional(st, "lm_head.weight")?.or(read_optional(st, "output.weight")?)
    };
    if embed.len() != cfg.vocab_size * cfg.hidden_size {
        return Err(LoadError::ShapeMismatch(format!(
            "model.embed_tokens.weight has {} elements, expected {}",
            embed.len(),
            cfg.vocab_size * cfg.hidden_size,
        )));
    }
    if norm_final.len() != cfg.hidden_size {
        return Err(LoadError::ShapeMismatch(format!(
            "model.norm.weight has {} elements, expected {}",
            norm_final.len(),
            cfg.hidden_size,
        )));
    }
    Ok(GlobalWeights {
        embed_tokens: embed,
        norm_final,
        lm_head,
    })
}

/// Read every tensor for one decoder block, transposing each projection
/// so the kernel-time matmul is the straightforward
/// `X[1, hidden] · W[hidden, out]`.
pub fn load_layer(
    st: &SafeTensors,
    cfg: &Gemma4Config,
    i: usize,
) -> Result<LayerWeights, LoadError> {
    let p = |suffix: &str| format!("model.layers.{i}.{suffix}");
    let hidden = cfg.hidden_size;
    let inter = cfg.intermediate_size;
    let q_total = cfg.num_query_heads * cfg.head_dim;
    let kv_total = cfg.num_kv_heads * cfg.head_dim;
    let ple = cfg.ple_dim;

    let norm_pre_attn = read_or(st, &p("input_layernorm.weight"))?;
    let norm_post_attn = read_or(st, &p("post_attention_layernorm.weight"))?;
    // Gemma 2/3 ship the FFN-side norm under `pre_feedforward_layernorm`
    // (and the post-FFN equivalent). Older Gemma 1 had a single norm
    // pair; in that case we substitute unit-gain so the math still works.
    let norm_pre_ffn = read_first(
        st,
        &[
            &p("pre_feedforward_layernorm.weight"),
            &p("pre_ffw_layernorm.weight"),
            &p("mlp_norm.weight"),
        ],
    )?
    .unwrap_or_else(|| vec![1.0; hidden]);
    let norm_post_ffn = read_first(
        st,
        &[
            &p("post_feedforward_layernorm.weight"),
            &p("post_ffw_layernorm.weight"),
        ],
    )?
    .unwrap_or_else(|| vec![1.0; hidden]);

    let w_q = transpose(read_or(st, &p("self_attn.q_proj.weight"))?, q_total, hidden)?;
    let w_k = transpose(read_or(st, &p("self_attn.k_proj.weight"))?, kv_total, hidden)?;
    let w_v = transpose(read_or(st, &p("self_attn.v_proj.weight"))?, kv_total, hidden)?;
    let w_o = transpose(read_or(st, &p("self_attn.o_proj.weight"))?, hidden, q_total)?;

    let w_gate = transpose(read_or(st, &p("mlp.gate_proj.weight"))?, inter, hidden)?;
    let w_up = transpose(read_or(st, &p("mlp.up_proj.weight"))?, inter, hidden)?;
    let w_down = transpose(read_or(st, &p("mlp.down_proj.weight"))?, hidden, inter)?;

    // PLE (Per-Layer Embedding) repair channel — Gemma 4 specific.
    // Earlier Gemma checkpoints don't have it, so absence is OK: the
    // substituted zero matrix makes ple_inject a no-op.
    let w_repair = match read_optional(st, &p("ple.repair.weight"))? {
        Some(v) => transpose(v, hidden, ple)?,
        None => vec![0.0; ple * hidden],
    };
    let ple_table = read_optional(st, "model.ple_embeddings")?
        .unwrap_or_else(|| vec![0.0; cfg.vocab_size * cfg.num_layers * ple]);

    Ok(LayerWeights {
        norm_pre_attn,
        norm_post_attn,
        norm_pre_ffn,
        norm_post_ffn,
        w_q,
        w_k,
        w_v,
        w_o,
        w_gate,
        w_up,
        w_down,
        w_repair,
        ple_table,
    })
}

/// Load every layer + the globals in one call. Callers needing
/// progressive loading (multi-GB models on memory-pressured devices)
/// use `load_global` / `load_layer` directly and persist each tensor
/// before reading the next.
pub fn load_model(
    st: &SafeTensors,
    cfg: &Gemma4Config,
) -> Result<(GlobalWeights, Vec<LayerWeights>), LoadError> {
    let global = load_global(st, cfg)?;
    let mut layers = Vec::with_capacity(cfg.num_layers);
    for i in 0..cfg.num_layers {
        layers.push(load_layer(st, cfg, i)?);
    }
    Ok((global, layers))
}

fn read_or(st: &SafeTensors, name: &str) -> Result<Vec<f32>, LoadError> {
    if st.get(name).is_none() {
        return Err(LoadError::Missing(name.to_string()));
    }
    st.as_f32(name)
        .ok_or_else(|| LoadError::UnsupportedDtype(name.to_string()))
}

fn read_optional(st: &SafeTensors, name: &str) -> Result<Option<Vec<f32>>, LoadError> {
    if st.get(name).is_none() {
        return Ok(None);
    }
    Ok(Some(
        st.as_f32(name)
            .ok_or_else(|| LoadError::UnsupportedDtype(name.to_string()))?,
    ))
}

fn read_first(st: &SafeTensors, candidates: &[&str]) -> Result<Option<Vec<f32>>, LoadError> {
    for name in candidates {
        if let Some(v) = read_optional(st, name)? {
            return Ok(Some(v));
        }
    }
    Ok(None)
}

/// Rectangular transpose `[rows, cols] -> [cols, rows]` for f32.
fn transpose(input: Vec<f32>, rows: usize, cols: usize) -> Result<Vec<f32>, LoadError> {
    if input.len() != rows * cols {
        return Err(LoadError::ShapeMismatch(format!(
            "expected {rows}x{cols} = {} elements, got {}",
            rows * cols,
            input.len()
        )));
    }
    let mut out = vec![0.0; rows * cols];
    for r in 0..rows {
        for c in 0..cols {
            out[c * rows + r] = input[r * cols + c];
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transpose_round_trips() {
        let m = vec![1.0_f32, 2.0, 3.0, 4.0, 5.0, 6.0]; // 2x3
        let t = transpose(m.clone(), 2, 3).unwrap(); // 3x2
        let back = transpose(t, 3, 2).unwrap(); // 2x3
        assert_eq!(back, m);
    }

    #[test]
    fn transpose_matches_hand_computed() {
        // [[1,2,3],[4,5,6]] -> [[1,4],[2,5],[3,6]]
        let m = vec![1.0_f32, 2.0, 3.0, 4.0, 5.0, 6.0];
        let t = transpose(m, 2, 3).unwrap();
        assert_eq!(t, vec![1.0, 4.0, 2.0, 5.0, 3.0, 6.0]);
    }

    #[test]
    fn transpose_rejects_shape_mismatch() {
        let m = vec![1.0_f32, 2.0, 3.0];
        assert!(transpose(m, 2, 3).is_err());
    }
}
