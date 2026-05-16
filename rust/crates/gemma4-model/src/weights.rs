//! Per-layer weight buffers. Real load wires these to `mmap` views over a
//! `model.safetensors` shard. Placeholder owning-Vec shape is used in tests.

/// Owning buffers for one decoder layer's weights. F32 for the parity
/// oracle path; the production path will replace each `Vec<f32>` with a
/// BF16/Q4_K view backed by mmap.
#[derive(Clone)]
pub struct LayerWeights {
    pub norm_pre_attn: Vec<f32>,    // [hidden]
    pub norm_post_attn: Vec<f32>,   // [hidden]
    pub norm_pre_ffn: Vec<f32>,     // [hidden]
    pub norm_post_ffn: Vec<f32>,    // [hidden]

    // QKV projections.
    pub w_q: Vec<f32>,              // [hidden, num_q_heads * head_dim]
    pub w_k: Vec<f32>,              // [hidden, num_kv_heads * head_dim]
    pub w_v: Vec<f32>,              // [hidden, num_kv_heads * head_dim]
    pub w_o: Vec<f32>,              // [num_q_heads * head_dim, hidden]

    // SwiGLU MLP.
    pub w_gate: Vec<f32>,           // [hidden, intermediate]
    pub w_up: Vec<f32>,             // [hidden, intermediate]
    pub w_down: Vec<f32>,           // [intermediate, hidden]

    // PLE "repair" channel.
    pub w_repair: Vec<f32>,         // [ple_dim, hidden]
    pub ple_table: Vec<f32>,        // [vocab, ple_dim]   (sparse-loaded in practice)
}

/// Top-level (non-per-layer) weights.
pub struct GlobalWeights {
    pub embed_tokens: Vec<f32>,     // [vocab, hidden]
    pub norm_final: Vec<f32>,       // [hidden]
    pub lm_head: Option<Vec<f32>>,  // None when tied to embed_tokens
}
