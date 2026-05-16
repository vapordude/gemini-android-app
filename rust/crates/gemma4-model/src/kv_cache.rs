//! Per-layer KV cache with support for cross-layer aliasing on the
//! early "shared-KV" layers of Gemma 4 E2B.
//!
//! Layout per layer: `[max_seq, num_kv_heads, head_dim]`. Two such arrays
//! per layer (K and V). The cache is `&mut`-borrowed by the attention
//! kernel at each step and accumulates K/V from the current token.

use crate::config::Gemma4Config;

pub struct KvCache {
    // Flat storage: one big buffer per K and V, sliced per layer.
    pub k: Vec<f32>,
    pub v: Vec<f32>,
    pub max_seq: usize,
    pub num_layers: usize,
    pub num_kv_heads: usize,
    pub head_dim: usize,
    /// Number of physical layers — equals `num_layers - kv_shared_layers`
    /// plus 1 (for the layer that "owns" the shared cache). For layers in
    /// `0..kv_shared_layers`, [`physical_layer`] returns the same index, so
    /// they all alias into one logical buffer.
    pub kv_shared_layers: usize,
    /// Highest position index written so far (length of valid prefix).
    pub seq_len: usize,
}

impl KvCache {
    pub fn new(cfg: &Gemma4Config) -> Self {
        let per_layer = cfg.max_position * cfg.num_kv_heads * cfg.head_dim;
        // Layout: layer i -> slice [i * per_layer .. (i+1) * per_layer].
        // For shared-KV layers we don't bother de-duplicating storage;
        // physical_layer() routes attention reads to the canonical layer 0
        // so reads remain consistent, and writes from layers 1..shared
        // are skipped (the caller checks is_kv_shared_layer).
        let total = cfg.num_layers * per_layer;
        KvCache {
            k: vec![0.0; total],
            v: vec![0.0; total],
            max_seq: cfg.max_position,
            num_layers: cfg.num_layers,
            num_kv_heads: cfg.num_kv_heads,
            head_dim: cfg.head_dim,
            kv_shared_layers: cfg.kv_shared_layers,
            seq_len: 0,
        }
    }

    /// Returns the physical layer index whose K/V buffer a read at
    /// `layer_idx` should target. For shared-KV early layers, all reads
    /// route to layer 0 — that's the one that gets written.
    pub fn physical_layer(&self, layer_idx: usize) -> usize {
        if layer_idx < self.kv_shared_layers { 0 } else { layer_idx }
    }

    fn layer_slice<'a>(&self, layer_idx: usize, buf: &'a [f32]) -> &'a [f32] {
        let phys = self.physical_layer(layer_idx);
        let per = self.max_seq * self.num_kv_heads * self.head_dim;
        let start = phys * per;
        &buf[start..start + per]
    }

    /// Append one token's K and V vectors to the cache for `layer_idx`.
    /// `k_token` and `v_token` must have shape `[num_kv_heads * head_dim]`.
    /// For shared-KV early layers (`layer_idx < kv_shared_layers`), only
    /// layer 0 writes; other early layers skip (they alias on read).
    pub fn append(&mut self, layer_idx: usize, pos: usize, k_token: &[f32], v_token: &[f32]) {
        let per_token = self.num_kv_heads * self.head_dim;
        assert_eq!(k_token.len(), per_token);
        assert_eq!(v_token.len(), per_token);
        if layer_idx < self.kv_shared_layers && layer_idx != 0 {
            return; // alias on read; skip write
        }
        let per = self.max_seq * per_token;
        let phys = self.physical_layer(layer_idx);
        let start = phys * per + pos * per_token;
        self.k[start..start + per_token].copy_from_slice(k_token);
        self.v[start..start + per_token].copy_from_slice(v_token);
    }

    /// View of K for layer at positions 0..pos (length `pos * num_kv_heads * head_dim`).
    pub fn k_view(&self, layer_idx: usize, pos: usize) -> &[f32] {
        let layer = self.layer_slice(layer_idx, &self.k);
        &layer[..pos * self.num_kv_heads * self.head_dim]
    }
    pub fn v_view(&self, layer_idx: usize, pos: usize) -> &[f32] {
        let layer = self.layer_slice(layer_idx, &self.v);
        &layer[..pos * self.num_kv_heads * self.head_dim]
    }

    /// Mark the current sequence length after writing position `pos`.
    pub fn set_seq_len(&mut self, len: usize) { self.seq_len = len; }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shared_kv_layers_alias_on_read() {
        let mut cfg = Gemma4Config::e2b_placeholder();
        cfg.num_layers = 4;
        cfg.kv_shared_layers = 2;
        cfg.max_position = 8;
        cfg.num_kv_heads = 1;
        cfg.head_dim = 2;
        let mut cache = KvCache::new(&cfg);
        let k = [1.0_f32, 2.0];
        let v = [3.0_f32, 4.0];
        cache.append(0, 0, &k, &v);
        // Layer 1 (still in shared range) reads the same K as layer 0.
        assert_eq!(cache.k_view(0, 1), cache.k_view(1, 1));
        // Layer 2 (past shared range) reads its own slice — currently zero.
        assert_eq!(cache.k_view(2, 1), &[0.0, 0.0]);
    }

    #[test]
    fn shared_kv_skip_write_from_non_canonical_layer() {
        let mut cfg = Gemma4Config::e2b_placeholder();
        cfg.num_layers = 4;
        cfg.kv_shared_layers = 2;
        cfg.max_position = 8;
        cfg.num_kv_heads = 1;
        cfg.head_dim = 2;
        let mut cache = KvCache::new(&cfg);
        // Layer 1 is in the shared range — its write should be skipped.
        cache.append(1, 0, &[9.0, 9.0], &[9.0, 9.0]);
        // Both layer 0 and layer 1 read zeros (since canonical layer 0 was
        // never written).
        assert_eq!(cache.k_view(0, 1), &[0.0, 0.0]);
        assert_eq!(cache.k_view(1, 1), &[0.0, 0.0]);
    }
}
