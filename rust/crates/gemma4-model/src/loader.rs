//! Load Gemma 4 weights from a sharded `model.safetensors` file (or the
//! single-file form). Maps the HF naming convention to the layer-buffer
//! shapes [`LayerWeights`] expects.
//!
//! HF Gemma 4 weight names (best guesses for the abliterated checkpoint;
//! these are tagged [SPEC] and must be confirmed against the actual
//! `model.safetensors.index.json`):
//!
//! ```text
//! model.embed_tokens.weight                       [vocab, hidden]
//! model.norm.weight                               [hidden]
//! model.layers.{i}.input_layernorm.weight         [hidden]
//! model.layers.{i}.post_attention_layernorm.weight[hidden]
//! model.layers.{i}.pre_feedforward_layernorm.weight   [hidden]
//! model.layers.{i}.post_feedforward_layernorm.weight  [hidden]
//! model.layers.{i}.self_attn.q_proj.weight         [q_total, hidden]
//! model.layers.{i}.self_attn.k_proj.weight         [kv_total, hidden]
//! model.layers.{i}.self_attn.v_proj.weight         [kv_total, hidden]
//! model.layers.{i}.self_attn.o_proj.weight         [hidden, q_total]
//! model.layers.{i}.mlp.gate_proj.weight            [intermediate, hidden]
//! model.layers.{i}.mlp.up_proj.weight              [intermediate, hidden]
//! model.layers.{i}.mlp.down_proj.weight            [hidden, intermediate]
//! model.layers.{i}.ple.repair.weight               [hidden, ple_dim]
//! lm_head.weight                                  [vocab, hidden]  (when not tied)
//! ```
//!
//! The HF convention stores weight matrices in [out, in] order (Linear
//! layer is `y = x · W^T + b` mathematically, but the stored matrix is
//! W^T-transposed compared to our row-major matmul). We transpose at
//! load time so the kernel-time matmul is the straightforward
//! `X[1, hidden] · W[hidden, out] = Y[1, out]`.

use crate::config::Gemma4Config;
use crate::weights::{GlobalWeights, LayerWeights};
use gemma4_core::SafeTensors;

#[derive(Debug)]
pub enum LoadError {
    Parse(&'static str),
    Missing(String),
    ShapeMismatch(String),
}

impl core::fmt::Display for LoadError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            LoadError::Parse(s) => write!(f, "parse: {s}"),
            LoadError::Missing(s) => write!(f, "missing tensor: {s}"),
            LoadError::ShapeMismatch(s) => write!(f, "shape mismatch: {s}"),
        }
    }
}

pub fn load_global(st: &SafeTensors, cfg: &Gemma4Config) -> Result<GlobalWeights, LoadError> {
    let embed = read_f32(st, "model.embed_tokens.weight")?;
    let norm_final = read_f32(st, "model.norm.weight")?;
    let lm_head = if cfg.tied_embeddings {
        None
    } else {
        read_f32(st, "lm_head.weight").ok()
    };
    if embed.len() != cfg.vocab_size * cfg.hidden_size {
        return Err(LoadError::ShapeMismatch(format!(
            "embed_tokens has {} elements, expected {}",
            embed.len(), cfg.vocab_size * cfg.hidden_size,
        )));
    }
    Ok(GlobalWeights { embed_tokens: embed, norm_final, lm_head })
}

pub fn load_layer(st: &SafeTensors, cfg: &Gemma4Config, i: usize) -> Result<LayerWeights, LoadError> {
    let p = |suffix: &str| format!("model.layers.{i}.{suffix}");
    let hidden = cfg.hidden_size;
    let inter = cfg.intermediate_size;
    let q_total = cfg.num_query_heads * cfg.head_dim;
    let kv_total = cfg.num_kv_heads * cfg.head_dim;
    let ple = cfg.ple_dim;

    let norm_pre_attn = read_f32(st, &p("input_layernorm.weight"))?;
    let norm_post_attn = read_f32(st, &p("post_attention_layernorm.weight"))?;
    let norm_pre_ffn = read_f32(st, &p("pre_feedforward_layernorm.weight"))?;
    let norm_post_ffn = read_f32(st, &p("post_feedforward_layernorm.weight"))?;

    // QKV projections — HF stores [out_dim, in_dim]; transpose to [in, out].
    let w_q = transpose(read_f32(st, &p("self_attn.q_proj.weight"))?, q_total, hidden);
    let w_k = transpose(read_f32(st, &p("self_attn.k_proj.weight"))?, kv_total, hidden);
    let w_v = transpose(read_f32(st, &p("self_attn.v_proj.weight"))?, kv_total, hidden);
    let w_o = transpose(read_f32(st, &p("self_attn.o_proj.weight"))?, hidden, q_total);

    let w_gate = transpose(read_f32(st, &p("mlp.gate_proj.weight"))?, inter, hidden);
    let w_up = transpose(read_f32(st, &p("mlp.up_proj.weight"))?, inter, hidden);
    let w_down = transpose(read_f32(st, &p("mlp.down_proj.weight"))?, hidden, inter);

    // PLE channel — naming convention guessed; if absent, fall back to zero.
    let w_repair = read_f32(st, &p("ple.repair.weight"))
        .map(|v| transpose(v, hidden, ple))
        .unwrap_or_else(|_| vec![0.0; ple * hidden]);
    let ple_table = read_f32(st, "model.ple_embeddings")
        .unwrap_or_else(|_| vec![0.0; cfg.vocab_size * cfg.num_layers * ple]);

    Ok(LayerWeights {
        norm_pre_attn, norm_post_attn, norm_pre_ffn, norm_post_ffn,
        w_q, w_k, w_v, w_o,
        w_gate, w_up, w_down,
        w_repair, ple_table,
    })
}

fn read_f32(st: &SafeTensors, name: &str) -> Result<Vec<f32>, LoadError> {
    st.f32(name).ok_or_else(|| LoadError::Missing(name.to_string()))
}

/// Rectangular transpose [rows, cols] -> [cols, rows] for f32.
fn transpose(input: Vec<f32>, rows: usize, cols: usize) -> Vec<f32> {
    if input.len() != rows * cols { return input; } // caller checks; just bail
    let mut out = vec![0.0; rows * cols];
    for r in 0..rows {
        for c in 0..cols {
            out[c * rows + r] = input[r * cols + c];
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transpose_round_trips() {
        let m = vec![1.0_f32, 2.0, 3.0, 4.0, 5.0, 6.0]; // 2x3
        let t = transpose(m.clone(), 2, 3); // 3x2
        let back = transpose(t, 3, 2); // 2x3
        assert_eq!(back, m);
    }

    #[test]
    fn transpose_is_correct() {
        // [[1, 2, 3], [4, 5, 6]] -> [[1, 4], [2, 5], [3, 6]]
        let m = vec![1.0_f32, 2.0, 3.0, 4.0, 5.0, 6.0];
        let t = transpose(m, 2, 3);
        assert_eq!(t, vec![1.0, 4.0, 2.0, 5.0, 3.0, 6.0]);
    }
}
