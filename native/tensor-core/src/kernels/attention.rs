//! Scaled dot-product attention. Single-query (decode) path with optional
//! sliding-window mask. Gemma 3+ uses SWA in alternating layers; v0
//! supports it via the `window` parameter.

use super::softmax::softmax_f32;

/// Decode-mode SDPA. Query is a single position; keys and values are the
/// running KV cache for this head.
///
/// Shapes (single head):
///   q:   [head_dim]
///   k:   [seq_len, head_dim] (row-major, position-contiguous)
///   v:   [seq_len, head_dim]
///   out: [head_dim]
///
/// If `window` is `Some(w)`, only the last `w` keys participate in the
/// attention; older keys get -inf scores.
pub fn sdpa_decode_f32(
    q: &[f32],
    k: &[f32],
    v: &[f32],
    out: &mut [f32],
    head_dim: usize,
    seq_len: usize,
    window: Option<usize>,
) {
    assert_eq!(q.len(), head_dim);
    assert_eq!(k.len(), seq_len * head_dim);
    assert_eq!(v.len(), seq_len * head_dim);
    assert_eq!(out.len(), head_dim);
    if seq_len == 0 {
        out.fill(0.0);
        return;
    }
    let scale = 1.0f32 / (head_dim as f32).sqrt();
    let first_visible = match window {
        Some(w) if seq_len > w => seq_len - w,
        _ => 0,
    };

    let mut scores = vec![f32::NEG_INFINITY; seq_len];
    for t in first_visible..seq_len {
        let k_row = &k[t * head_dim..(t + 1) * head_dim];
        let mut s = 0.0f32;
        for i in 0..head_dim {
            s += q[i] * k_row[i];
        }
        scores[t] = s * scale;
    }
    softmax_f32(&mut scores);

    out.fill(0.0);
    for t in first_visible..seq_len {
        let w = scores[t];
        if w == 0.0 {
            continue;
        }
        let v_row = &v[t * head_dim..(t + 1) * head_dim];
        for i in 0..head_dim {
            out[i] += w * v_row[i];
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn attention_to_single_key_returns_value() {
        let head_dim = 4;
        let q = vec![1.0, 0.0, 0.0, 0.0];
        let k = vec![1.0, 0.0, 0.0, 0.0]; // strong match
        let v = vec![0.5, 0.25, 0.125, 0.0];
        let mut out = vec![0.0; head_dim];
        sdpa_decode_f32(&q, &k, &v, &mut out, head_dim, 1, None);
        for i in 0..head_dim {
            assert!(
                (out[i] - v[i]).abs() < 1e-5,
                "i={i} got {} expected {}",
                out[i],
                v[i]
            );
        }
    }

    #[test]
    fn sliding_window_masks_old_tokens() {
        let head_dim = 2;
        let q = vec![1.0, 0.0];
        // Three keys; first one has the strongest match.
        let k = vec![
            1.0, 0.0, // pos 0 — best raw match
            0.1, 0.0, // pos 1
            0.1, 0.0, // pos 2
        ];
        let v = vec![10.0, 0.0, 1.0, 0.0, 1.0, 0.0];
        let mut out_full = vec![0.0; head_dim];
        let mut out_windowed = vec![0.0; head_dim];
        sdpa_decode_f32(&q, &k, &v, &mut out_full, head_dim, 3, None);
        sdpa_decode_f32(&q, &k, &v, &mut out_windowed, head_dim, 3, Some(2));
        // Full attention sees the strong match at pos 0 → result skews toward 10.
        // Windowed (last 2 only) cannot see pos 0 → result should be much lower.
        assert!(out_full[0] > out_windowed[0]);
    }
}
