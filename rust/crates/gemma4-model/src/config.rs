//! Gemma 4 E2B architecture constants. Values tagged `[SPEC]` come from the
//! `config.json` shipped with the weights — fill in once the user pastes
//! the exact spec.

#[derive(Clone, Copy, Debug)]
pub struct Gemma4Config {
    // Decoder geometry (confirmed from HF model card).
    pub num_layers: usize,           // 35
    pub kv_shared_layers: usize,     // 20

    // [SPEC] — fill from config.json
    pub hidden_size: usize,
    pub intermediate_size: usize,
    pub num_query_heads: usize,
    pub num_kv_heads: usize,
    pub head_dim: usize,             // typically hidden_size / num_query_heads
    pub vocab_size: usize,
    pub max_position: usize,
    pub rope_theta: f32,             // typically 10000.0 or 1e6
    pub rms_norm_eps: f32,           // typically 1e-5 or 1e-6

    // PLE (Per-Layer Embedding) repair channel — Gemma 4 specific.
    pub ple_dim: usize,              // [SPEC]
    pub tied_embeddings: bool,       // [SPEC] true if lm_head == embed_tokens

    // Special tokens (resolved from tokenizer.json on load).
    pub bos_token_id: u32,
    pub eos_token_id: u32,
    pub pad_token_id: u32,
    pub image_token_id: u32,
    pub audio_token_id: u32,
}

impl Gemma4Config {
    /// Pre-spec placeholder values. Replace via parsed config.json before
    /// loading real weights. Tests use this so the math wiring compiles even
    /// before the spec is final.
    pub fn e2b_placeholder() -> Self {
        Gemma4Config {
            num_layers: 35,
            kv_shared_layers: 20,
            hidden_size: 2048,            // [SPEC] placeholder
            intermediate_size: 8192,      // [SPEC] placeholder
            num_query_heads: 16,          // [SPEC] placeholder
            num_kv_heads: 4,              // [SPEC] placeholder (GQA factor 4)
            head_dim: 128,                // [SPEC] = hidden / q_heads, placeholder
            vocab_size: 262_144,          // [SPEC] placeholder (Gemma family)
            max_position: 8192,           // [SPEC] placeholder
            rope_theta: 10_000.0,         // [SPEC] verify
            rms_norm_eps: 1.0e-5,         // [SPEC] verify
            ple_dim: 256,                 // [SPEC] placeholder
            tied_embeddings: false,       // [SPEC] verify
            bos_token_id: 2,
            eos_token_id: 1,
            pad_token_id: 0,
            image_token_id: 256_000,
            audio_token_id: 256_001,
        }
    }

    /// True for `layer_idx < kv_shared_layers`. Per HF model card: the early
    /// layers of Gemma 4 E2B share KV cache via the "selective activation"
    /// mechanism that gives the variant its 2B-effective parameter count.
    pub fn is_kv_shared_layer(&self, layer_idx: usize) -> bool {
        layer_idx < self.kv_shared_layers
    }
}
