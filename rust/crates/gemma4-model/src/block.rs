//! Decoder block forward pass — one layer, F32 parity reference.
//!
//! See the crate-level docs for the full step list. This file is the
//! authoritative wiring of:
//!
//! ```text
//! pre_attn_norm  →  Q/K/V proj  →  RoPE  →  KV append  →  GQA attn  →
//! O proj  →  post_attn_norm  →  residual  →
//! pre_ffn_norm  →  SwiGLU  →  W_down  →  post_ffn_norm  →  residual  →
//! PLE inject
//! ```

use crate::config::Gemma4Config;
use crate::kv_cache::KvCache;
use crate::weights::LayerWeights;
use gemma4_ops::{matmul_f32, rmsnorm_f32, rope_apply_f32, gqa_attention_f32, swiglu_f32};

/// Forward one decoder layer in F32, for the *current single token* at
/// position `pos`. Operates in place on `x` (shape `[hidden_size]`).
/// `token_id` drives the PLE channel.
///
/// Allocates a few scratch vectors per call — fine for the parity oracle;
/// the production path will route these through the arena allocator.
#[allow(clippy::too_many_arguments)]
pub fn decoder_block_f32(
    x: &mut [f32],
    cfg: &Gemma4Config,
    layer_idx: usize,
    pos: usize,
    weights: &LayerWeights,
    kv: &mut KvCache,
    rope_cos: &[f32],
    rope_sin: &[f32],
    token_id: u32,
) {
    let hidden = cfg.hidden_size;
    let inter = cfg.intermediate_size;
    let q_total = cfg.num_query_heads * cfg.head_dim;
    let kv_total = cfg.num_kv_heads * cfg.head_dim;
    assert_eq!(x.len(), hidden);

    // ── ATTENTION SUBLAYER ────────────────────────────────────────────────

    // h = pre_attn_norm(x)
    let mut h = vec![0.0_f32; hidden];
    rmsnorm_f32(x, &weights.norm_pre_attn, cfg.rms_norm_eps, &mut h);

    // Q = h · W_q,  K = h · W_k,  V = h · W_v
    let mut q = vec![0.0_f32; q_total];
    let mut k = vec![0.0_f32; kv_total];
    let mut v = vec![0.0_f32; kv_total];
    matmul_f32(&h, 1, hidden, &weights.w_q, hidden, q_total, &[], &mut q);
    matmul_f32(&h, 1, hidden, &weights.w_k, hidden, kv_total, &[], &mut k);
    matmul_f32(&h, 1, hidden, &weights.w_v, hidden, kv_total, &[], &mut v);

    // RoPE on Q and K.
    rope_apply_f32(&mut q, 1, cfg.num_query_heads, cfg.head_dim, rope_cos, rope_sin, pos);
    rope_apply_f32(&mut k, 1, cfg.num_kv_heads, cfg.head_dim, rope_cos, rope_sin, pos);

    // Append to KV cache (skipped silently for shared-KV non-canonical layers).
    kv.append(layer_idx, pos, &k, &v);
    let kv_seq_len = pos + 1;
    let k_view = kv.k_view(layer_idx, kv_seq_len);
    let v_view = kv.v_view(layer_idx, kv_seq_len);

    // GQA attention.
    let mut attn_out = vec![0.0_f32; q_total];
    gqa_attention_f32(
        &q, k_view, v_view, &mut attn_out,
        /*seq_len=*/1, kv_seq_len,
        cfg.num_query_heads, cfg.num_kv_heads, cfg.head_dim,
        /*mask_causal=*/true,
    );

    // O projection back to hidden.
    let mut o = vec![0.0_f32; hidden];
    matmul_f32(&attn_out, 1, q_total, &weights.w_o, q_total, hidden, &[], &mut o);

    // post_attn_norm (Gemma 4 double-norm).
    let mut h2 = vec![0.0_f32; hidden];
    rmsnorm_f32(&o, &weights.norm_post_attn, cfg.rms_norm_eps, &mut h2);

    // x += h2
    for i in 0..hidden { x[i] += h2[i]; }

    // ── FFN SUBLAYER ──────────────────────────────────────────────────────

    // h = pre_ffn_norm(x)
    rmsnorm_f32(x, &weights.norm_pre_ffn, cfg.rms_norm_eps, &mut h);

    // gate = h · W_gate, up = h · W_up   (both at intermediate-dim)
    let mut gate = vec![0.0_f32; inter];
    let mut up = vec![0.0_f32; inter];
    matmul_f32(&h, 1, hidden, &weights.w_gate, hidden, inter, &[], &mut gate);
    matmul_f32(&h, 1, hidden, &weights.w_up, hidden, inter, &[], &mut up);

    // SwiGLU pointwise.
    let mut swiglu_out = vec![0.0_f32; inter];
    swiglu_f32(&gate, &up, &mut swiglu_out);

    // Back to hidden via W_down.
    let mut ffn_out = vec![0.0_f32; hidden];
    matmul_f32(&swiglu_out, 1, inter, &weights.w_down, inter, hidden, &[], &mut ffn_out);

    // post_ffn_norm (Gemma 4 double-norm).
    let mut ffn_normed = vec![0.0_f32; hidden];
    rmsnorm_f32(&ffn_out, &weights.norm_post_ffn, cfg.rms_norm_eps, &mut ffn_normed);

    // x += ffn_normed
    for i in 0..hidden { x[i] += ffn_normed[i]; }

    // ── PLE INJECT ────────────────────────────────────────────────────────
    // x += W_repair · ple_table[token_id, layer_idx]
    //
    // [SPEC]: exact placement (after both residuals — current best guess)
    // and gating (none — additive injection) come from the model config.
    // Wired as a single function so it's one place to update.
    ple_inject(x, cfg, layer_idx, token_id, weights);
}

fn ple_inject(
    x: &mut [f32],
    cfg: &Gemma4Config,
    layer_idx: usize,
    token_id: u32,
    weights: &LayerWeights,
) {
    let hidden = cfg.hidden_size;
    let ple = cfg.ple_dim;
    // Look up the (token_id, layer_idx) row of the PLE table. The table is
    // typically `[vocab, num_layers, ple_dim]` row-major.
    let row_off = (token_id as usize * cfg.num_layers + layer_idx) * ple;
    if row_off + ple > weights.ple_table.len() {
        // Test fixtures may pass an empty/short table — no-op then.
        return;
    }
    let ple_row = &weights.ple_table[row_off..row_off + ple];
    // x += W_repair · ple_row    (W_repair: [ple_dim, hidden] row-major)
    if weights.w_repair.len() < ple * hidden { return; }
    for h_i in 0..hidden {
        let mut acc = 0.0_f32;
        for p in 0..ple {
            acc += ple_row[p] * weights.w_repair[p * hidden + h_i];
        }
        x[h_i] += acc;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use gemma4_ops::rope::rope_freqs_f32;

    /// Decoder block runs end-to-end with placeholder weights and produces a
    /// finite, non-NaN output. This is a smoke test — it doesn't verify
    /// numerical correctness against a reference (that's the PyTorch parity
    /// harness in a future commit) but it does prove the wiring is internally
    /// consistent.
    #[test]
    fn block_forward_completes_without_panic() {
        let mut cfg = Gemma4Config::e2b_placeholder();
        // Shrink for fast tests.
        cfg.num_layers = 4;
        cfg.kv_shared_layers = 1;
        cfg.hidden_size = 8;
        cfg.intermediate_size = 16;
        cfg.num_query_heads = 4;
        cfg.num_kv_heads = 2;
        cfg.head_dim = 2;
        cfg.max_position = 4;
        cfg.ple_dim = 4;
        cfg.vocab_size = 16;

        let hidden = cfg.hidden_size;
        let inter = cfg.intermediate_size;
        let q_total = cfg.num_query_heads * cfg.head_dim;
        let kv_total = cfg.num_kv_heads * cfg.head_dim;
        let weights = LayerWeights {
            norm_pre_attn: vec![1.0; hidden],
            norm_post_attn: vec![1.0; hidden],
            norm_pre_ffn: vec![1.0; hidden],
            norm_post_ffn: vec![1.0; hidden],
            w_q: vec![0.01; hidden * q_total],
            w_k: vec![0.01; hidden * kv_total],
            w_v: vec![0.01; hidden * kv_total],
            w_o: vec![0.01; q_total * hidden],
            w_gate: vec![0.01; hidden * inter],
            w_up: vec![0.01; hidden * inter],
            w_down: vec![0.01; inter * hidden],
            w_repair: vec![0.0; cfg.ple_dim * hidden],
            ple_table: vec![0.0; cfg.vocab_size * cfg.num_layers * cfg.ple_dim],
        };
        let (cos, sin) = rope_freqs_f32(cfg.max_position, cfg.head_dim, cfg.rope_theta);
        let mut kv = KvCache::new(&cfg);
        let mut x: Vec<f32> = (0..hidden).map(|i| 0.1 * (i as f32)).collect();

        decoder_block_f32(&mut x, &cfg, 0, 0, &weights, &mut kv, &cos, &sin, /*token=*/5);
        for v in &x {
            assert!(v.is_finite(), "non-finite output: {v}");
        }
    }
}
