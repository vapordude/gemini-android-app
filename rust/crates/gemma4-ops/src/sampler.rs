//! Token sampler. Math:
//!
//! ```text
//!   p_i  = softmax(logits / temperature)
//!   top_k: keep only the k highest-probability tokens
//!   top_p: keep the smallest set whose cumulative probability ≥ p
//! ```
//!
//! Deterministic when `seed` is fixed — required for the parity harness.
//! Uses a hand-rolled xoshiro128++ PRNG so the crate stays dep-free.

use crate::softmax::softmax_inplace_f32;

pub struct SamplerCfg {
    pub temperature: f32,
    pub top_k: usize,    // 0 disables
    pub top_p: f32,      // 1.0 disables
    pub seed: u64,
}

pub fn sample_token(logits: &mut [f32], cfg: &SamplerCfg, rng: &mut Xoshiro) -> u32 {
    if cfg.temperature <= 0.0 {
        // Greedy: pick the argmax. The parity harness uses this path.
        return argmax(logits) as u32;
    }
    softmax_inplace_f32(logits, 1.0 / cfg.temperature);

    if cfg.top_k > 0 && cfg.top_k < logits.len() {
        // Find the k-th highest probability; zero anything below.
        let mut indexed: Vec<(f32, usize)> = logits.iter().copied()
            .enumerate().map(|(i, v)| (v, i)).collect();
        indexed.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(core::cmp::Ordering::Equal));
        let cutoff = indexed[cfg.top_k - 1].0;
        for v in logits.iter_mut() {
            if *v < cutoff { *v = 0.0; }
        }
        renormalize(logits);
    }

    if cfg.top_p < 1.0 && cfg.top_p > 0.0 {
        let mut indexed: Vec<(f32, usize)> = logits.iter().copied()
            .enumerate().map(|(i, v)| (v, i)).collect();
        indexed.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(core::cmp::Ordering::Equal));
        let mut cum = 0.0_f32;
        let mut keep = vec![false; logits.len()];
        for (v, idx) in &indexed {
            cum += v;
            keep[*idx] = true;
            if cum >= cfg.top_p { break; }
        }
        for i in 0..logits.len() {
            if !keep[i] { logits[i] = 0.0; }
        }
        renormalize(logits);
    }

    // Inverse-CDF sample with the PRNG.
    let r = rng.next_f32_uniform();
    let mut cum = 0.0_f32;
    for (i, &p) in logits.iter().enumerate() {
        cum += p;
        if r <= cum { return i as u32; }
    }
    (logits.len() - 1) as u32
}

fn argmax(v: &[f32]) -> usize {
    let mut best_i = 0;
    let mut best_v = v[0];
    for (i, &x) in v.iter().enumerate().skip(1) {
        if x > best_v { best_v = x; best_i = i; }
    }
    best_i
}

fn renormalize(v: &mut [f32]) {
    let s: f32 = v.iter().sum();
    if s <= 0.0 { return; }
    let inv = 1.0 / s;
    for x in v.iter_mut() { *x *= inv; }
}

/// xoshiro128++ — small, fast, well-tested PRNG. We keep the crate dep-free
/// rather than pulling in `rand`.
pub struct Xoshiro {
    s: [u32; 4],
}

impl Xoshiro {
    pub fn from_seed(seed: u64) -> Self {
        // SplitMix64 to expand a single seed into four state words.
        let mut z = seed;
        let mut next = || {
            z = z.wrapping_add(0x9E3779B97F4A7C15);
            let mut r = z;
            r = (r ^ (r >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
            r = (r ^ (r >> 27)).wrapping_mul(0x94D049BB133111EB);
            r ^ (r >> 31)
        };
        let a = next(); let b = next();
        Xoshiro {
            s: [
                a as u32, (a >> 32) as u32,
                b as u32, (b >> 32) as u32,
            ],
        }
    }

    pub fn next_u32(&mut self) -> u32 {
        let result = self.s[0]
            .wrapping_add(self.s[3])
            .rotate_left(7)
            .wrapping_add(self.s[0]);
        let t = self.s[1] << 9;
        self.s[2] ^= self.s[0];
        self.s[3] ^= self.s[1];
        self.s[1] ^= self.s[2];
        self.s[0] ^= self.s[3];
        self.s[2] ^= t;
        self.s[3] = self.s[3].rotate_left(11);
        result
    }

    pub fn next_f32_uniform(&mut self) -> f32 {
        // Map u32 → [0, 1) by discarding the bottom 8 bits.
        ((self.next_u32() >> 8) as f32) / ((1u32 << 24) as f32)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn argmax_is_greedy() {
        let mut logits = vec![1.0_f32, 5.0, 3.0, 2.0];
        let mut rng = Xoshiro::from_seed(0);
        let token = sample_token(&mut logits, &SamplerCfg {
            temperature: 0.0, top_k: 0, top_p: 1.0, seed: 0,
        }, &mut rng);
        assert_eq!(token, 1);
    }

    #[test]
    fn prng_is_deterministic() {
        let mut a = Xoshiro::from_seed(42);
        let mut b = Xoshiro::from_seed(42);
        for _ in 0..100 {
            assert_eq!(a.next_u32(), b.next_u32());
        }
    }

    #[test]
    fn top_k_one_collapses_to_argmax() {
        let mut logits = vec![1.0_f32, 5.0, 3.0, 2.0];
        let mut rng = Xoshiro::from_seed(123);
        let token = sample_token(&mut logits, &SamplerCfg {
            temperature: 1.0, top_k: 1, top_p: 1.0, seed: 0,
        }, &mut rng);
        assert_eq!(token, 1);
    }
}
