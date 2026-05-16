//! Scaled dot-product attention with grouped-query support.
//!
//! Math spec (Qwen memo):
//!
//! ```text
//!   Attention(Q, K, V) = softmax( Q·Kᵀ / √d_k  +  mask ) · V
//!
//!   mask_{ij} = 0       if j ≤ i  (causal)
//!             = -∞      if j > i
//! ```
//!
//! Gemma 4 uses Grouped-Query Attention: `num_query_heads > num_kv_heads`,
//! each KV head is broadcast to `(num_query_heads / num_kv_heads)` query
//! heads. We materialize the broadcast implicitly inside the loop.

use crate::softmax::softmax_inplace_f32;

/// Compute one forward pass of GQA attention for a single batch element.
///
/// Shapes (all row-major):
///   q:        [seq_len, num_q_heads,  head_dim]
///   k_cache:  [seq_len, num_kv_heads, head_dim]
///   v_cache:  [seq_len, num_kv_heads, head_dim]
///   out:      [seq_len, num_q_heads,  head_dim]
///
/// `mask_causal=true` enforces `j ≤ i`. `kv_seq_len` lets us attend to a
/// prefix of the cache (full length when decoding from the start;
/// `prev_len + 1` when decoding one token at a time).
#[allow(clippy::too_many_arguments)]
pub fn gqa_attention_f32(
    q: &[f32],
    k_cache: &[f32],
    v_cache: &[f32],
    out: &mut [f32],
    seq_len: usize,
    kv_seq_len: usize,
    num_q_heads: usize,
    num_kv_heads: usize,
    head_dim: usize,
    mask_causal: bool,
) {
    assert!(num_kv_heads <= num_q_heads, "num_kv_heads must divide num_q_heads");
    assert_eq!(num_q_heads % num_kv_heads, 0, "Q/KV heads must be divisible for GQA");
    assert_eq!(q.len(), seq_len * num_q_heads * head_dim);
    assert_eq!(k_cache.len(), kv_seq_len * num_kv_heads * head_dim);
    assert_eq!(v_cache.len(), kv_seq_len * num_kv_heads * head_dim);
    assert_eq!(out.len(), seq_len * num_q_heads * head_dim);

    let scale = 1.0_f32 / (head_dim as f32).sqrt();
    let group_size = num_q_heads / num_kv_heads;

    let mut scores: Vec<f32> = vec![0.0; kv_seq_len];

    for s in 0..seq_len {
        for h_q in 0..num_q_heads {
            let h_kv = h_q / group_size;

            // Compute scores[j] = ⟨ q_{s,h_q}, k_{j,h_kv} ⟩ for j = 0..kv_seq_len.
            for j in 0..kv_seq_len {
                let q_off = (s * num_q_heads + h_q) * head_dim;
                let k_off = (j * num_kv_heads + h_kv) * head_dim;
                let mut acc: f32 = 0.0;
                for d in 0..head_dim {
                    acc += q[q_off + d] * k_cache[k_off + d];
                }
                // Apply causal mask BEFORE softmax. For one-token decode
                // (seq_len=1), s=0 corresponds to position kv_seq_len-1.
                if mask_causal {
                    // Effective query position in the full sequence:
                    let q_pos = kv_seq_len - seq_len + s;
                    if j > q_pos { acc = f32::NEG_INFINITY; }
                }
                scores[j] = acc;
            }

            // softmax with √d_k scaling folded in.
            softmax_inplace_f32(&mut scores, scale);

            // out[s, h_q, :] = Σ_j scores[j] · v_cache[j, h_kv, :]
            let out_off = (s * num_q_heads + h_q) * head_dim;
            for d in 0..head_dim { out[out_off + d] = 0.0; }
            for j in 0..kv_seq_len {
                let v_off = (j * num_kv_heads + h_kv) * head_dim;
                let p = scores[j];
                if p == 0.0 { continue; } // skip exactly-zero masked positions
                for d in 0..head_dim {
                    out[out_off + d] += p * v_cache[v_off + d];
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Attention with a single token, single head, attending to a single
    /// position should produce `softmax([qᵀk/√d]) · v = 1 · v = v`.
    #[test]
    fn attention_single_token_single_head_returns_v() {
        let head_dim = 4;
        let q = [1.0_f32, 0.0, 0.0, 0.0];
        let k = [1.0_f32, 0.0, 0.0, 0.0];
        let v = [7.0_f32, 8.0, 9.0, 10.0];
        let mut out = [0.0_f32; 4];
        gqa_attention_f32(
            &q, &k, &v, &mut out,
            /*seq_len=*/1, /*kv_seq_len=*/1,
            /*num_q_heads=*/1, /*num_kv_heads=*/1,
            head_dim,
            /*mask_causal=*/true,
        );
        for i in 0..4 { assert!((out[i] - v[i]).abs() < 1e-5); }
    }

    /// Equal scores → average of V rows.
    #[test]
    fn attention_uniform_scores_averages_v() {
        let head_dim = 2;
        // Q is zero — qᵀk = 0 for all j → softmax is uniform.
        let q = [0.0_f32, 0.0];
        let k = [1.0_f32, 0.0, 0.0, 1.0]; // 2 positions, each [.., ..]
        let v = [1.0_f32, 2.0, 3.0, 4.0]; // 2 positions: [1,2] and [3,4]
        let mut out = [0.0_f32; 2];
        gqa_attention_f32(
            &q, &k, &v, &mut out,
            /*seq_len=*/1, /*kv_seq_len=*/2,
            1, 1, head_dim, /*mask_causal=*/false,
        );
        // Average: [(1+3)/2, (2+4)/2] = [2, 3]
        assert!((out[0] - 2.0).abs() < 1e-5);
        assert!((out[1] - 3.0).abs() < 1e-5);
    }

    /// GQA broadcasts a single KV head to N query heads → both produce
    /// the same output for the same Q.
    #[test]
    fn gqa_kv_broadcast_produces_consistent_heads() {
        let head_dim = 2;
        let q = [1.0_f32, 0.0,  1.0, 0.0]; // 1 token × 2 q heads × 2 dim
        let k = [1.0_f32, 0.0]; // 1 token × 1 kv head × 2 dim
        let v = [5.0_f32, 6.0];
        let mut out = [0.0_f32; 4];
        gqa_attention_f32(
            &q, &k, &v, &mut out,
            1, 1, /*Q=*/2, /*KV=*/1, head_dim, true,
        );
        // Both q-heads see the same kv-head and the same q → identical out.
        assert!((out[0] - out[2]).abs() < 1e-5);
        assert!((out[1] - out[3]).abs() < 1e-5);
        assert!((out[0] - 5.0).abs() < 1e-5);
        assert!((out[1] - 6.0).abs() < 1e-5);
    }
}
