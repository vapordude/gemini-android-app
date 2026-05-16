//! Audio tower preprocessing.
//!
//! Pipeline for one audio clip:
//!
//! 1. **Decode**: caller delivers raw PCM (mono, f32, target sample rate).
//! 2. **Frame**: split into overlapping windows of `win_size` samples,
//!    hop `hop_size`.
//! 3. **Hann window**: multiply each frame by the window function.
//! 4. **STFT**: radix-2 Cooley-Tukey FFT per frame → complex spectrum.
//! 5. **Power**: |X|² → energy spectrum.
//! 6. **Mel filterbank**: project to `n_mels` mel bands.
//! 7. **Log**: stable log of (mel + epsilon).
//! 8. **Audio transformer**: same kernels as decoder, no causal mask.
//! 9. **Projector**: linear to text HIDDEN; `N_aud × HIDDEN` tokens
//!    slotted at `AUDIO_TOKEN` positions in the prompt.
//!
//! This crate covers steps 2-7. Step 1 is platform I/O; steps 8-9 reuse
//! gemma4-ops kernels via gemma4-model wiring once weights ship.
//!
//! No external dependencies. FFT is a hand-rolled in-place radix-2.

#![forbid(unsafe_op_in_unsafe_fn)]

/// Audio tower constants. Placeholder values reflect Whisper/USM-style
/// settings; Gemma 4's audio tower may differ -- adjust to spec.
#[derive(Clone, Copy, Debug)]
pub struct AudioConfig {
    pub sample_rate: usize,    // [SPEC] — e.g. 16000
    pub win_size: usize,       // [SPEC] — e.g. 400 (25 ms @ 16 kHz)
    pub hop_size: usize,       // [SPEC] — e.g. 160 (10 ms @ 16 kHz)
    pub n_fft: usize,          // [SPEC] — usually next power of 2 >= win_size, e.g. 512
    pub n_mels: usize,         // [SPEC] — e.g. 80
    pub mel_low_hz: f32,       // 0.0
    pub mel_high_hz: f32,      // sample_rate / 2
}

impl AudioConfig {
    pub fn placeholder() -> Self {
        AudioConfig {
            sample_rate: 16_000,
            win_size: 400,
            hop_size: 160,
            n_fft: 512,
            n_mels: 80,
            mel_low_hz: 0.0,
            mel_high_hz: 8_000.0,
        }
    }
}

/// Hann window of length n: `w[i] = 0.5 - 0.5·cos(2π·i / (n-1))`.
pub fn hann_window(n: usize) -> Vec<f32> {
    if n == 0 { return Vec::new(); }
    if n == 1 { return vec![1.0]; }
    let mut w = vec![0.0_f32; n];
    let denom = (n - 1) as f32;
    for i in 0..n {
        w[i] = 0.5 - 0.5 * (2.0 * core::f32::consts::PI * (i as f32) / denom).cos();
    }
    w
}

/// In-place radix-2 Cooley-Tukey FFT. `re` and `im` must be length n where
/// n is a power of two. Bit-reversal permutation then log2(n) stages.
pub fn fft(re: &mut [f32], im: &mut [f32]) {
    let n = re.len();
    assert_eq!(im.len(), n);
    assert!(n.is_power_of_two(), "FFT length must be a power of 2");
    if n <= 1 { return; }

    // Bit-reversal permutation.
    let mut j = 0_usize;
    for i in 1..n {
        let mut bit = n >> 1;
        while (j & bit) != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
    }

    // Butterflies.
    let mut len = 2_usize;
    while len <= n {
        let half = len / 2;
        let angle = -2.0 * core::f32::consts::PI / (len as f32);
        let wlen_re = angle.cos();
        let wlen_im = angle.sin();
        let mut i = 0;
        while i < n {
            let mut w_re = 1.0_f32;
            let mut w_im = 0.0_f32;
            for k in 0..half {
                let a_re = re[i + k];
                let a_im = im[i + k];
                let b_re = re[i + k + half] * w_re - im[i + k + half] * w_im;
                let b_im = re[i + k + half] * w_im + im[i + k + half] * w_re;
                re[i + k] = a_re + b_re;
                im[i + k] = a_im + b_im;
                re[i + k + half] = a_re - b_re;
                im[i + k + half] = a_im - b_im;
                let nw_re = w_re * wlen_re - w_im * wlen_im;
                let nw_im = w_re * wlen_im + w_im * wlen_re;
                w_re = nw_re;
                w_im = nw_im;
            }
            i += len;
        }
        len <<= 1;
    }
}

/// One STFT frame: window the input, zero-pad to n_fft, FFT, return |X|².
pub fn power_spectrum(frame: &[f32], window: &[f32], n_fft: usize) -> Vec<f32> {
    assert_eq!(frame.len(), window.len());
    let mut re = vec![0.0_f32; n_fft];
    let mut im = vec![0.0_f32; n_fft];
    for i in 0..frame.len() { re[i] = frame[i] * window[i]; }
    fft(&mut re, &mut im);
    let half = n_fft / 2 + 1;
    let mut out = vec![0.0_f32; half];
    for i in 0..half {
        out[i] = re[i] * re[i] + im[i] * im[i];
    }
    out
}

/// Hz <-> Mel scale conversion (Slaney's formula).
#[inline]
pub fn hz_to_mel(hz: f32) -> f32 {
    2595.0 * (1.0 + hz / 700.0).log10()
}

#[inline]
pub fn mel_to_hz(mel: f32) -> f32 {
    700.0 * (10.0_f32.powf(mel / 2595.0) - 1.0)
}

/// Build the mel filterbank as a dense matrix `[n_mels, n_fft/2 + 1]`. Each
/// row is a triangular filter overlapping its neighbours at half-height.
pub fn mel_filterbank(cfg: &AudioConfig) -> Vec<f32> {
    let half = cfg.n_fft / 2 + 1;
    let mel_low = hz_to_mel(cfg.mel_low_hz);
    let mel_high = hz_to_mel(cfg.mel_high_hz);
    // n_mels + 2 evenly-spaced points in mel space → n_mels triangular
    // filters whose peaks are at points 1..n_mels.
    let n_pts = cfg.n_mels + 2;
    let mut mel_points = vec![0.0_f32; n_pts];
    for i in 0..n_pts {
        mel_points[i] = mel_low + (mel_high - mel_low) * (i as f32) / (n_pts as f32 - 1.0);
    }
    let mut hz_points = vec![0.0_f32; n_pts];
    for i in 0..n_pts { hz_points[i] = mel_to_hz(mel_points[i]); }
    let mut bin_points = vec![0.0_f32; n_pts];
    for i in 0..n_pts {
        bin_points[i] = hz_points[i] * ((cfg.n_fft as f32) / (cfg.sample_rate as f32));
    }

    let mut fb = vec![0.0_f32; cfg.n_mels * half];
    for m in 0..cfg.n_mels {
        let l = bin_points[m];
        let c = bin_points[m + 1];
        let r = bin_points[m + 2];
        for k in 0..half {
            let f = k as f32;
            let w = if f < l || f > r {
                0.0
            } else if f < c {
                (f - l) / (c - l).max(1e-6)
            } else {
                (r - f) / (r - c).max(1e-6)
            };
            fb[m * half + k] = w;
        }
    }
    fb
}

/// Full preprocessing: PCM → log-mel-spectrogram. Output shape:
/// `[num_frames, n_mels]` row-major. `num_frames = (pcm.len() - win_size) / hop_size + 1`.
pub fn log_mel_spectrogram(pcm: &[f32], cfg: &AudioConfig) -> Vec<f32> {
    if pcm.len() < cfg.win_size { return Vec::new(); }
    let window = hann_window(cfg.win_size);
    let fb = mel_filterbank(cfg);
    let half = cfg.n_fft / 2 + 1;
    let num_frames = (pcm.len() - cfg.win_size) / cfg.hop_size + 1;
    let mut out = vec![0.0_f32; num_frames * cfg.n_mels];

    let mut frame = vec![0.0_f32; cfg.win_size];
    for f in 0..num_frames {
        let start = f * cfg.hop_size;
        frame.copy_from_slice(&pcm[start..start + cfg.win_size]);
        let pow = power_spectrum(&frame, &window, cfg.n_fft);
        for m in 0..cfg.n_mels {
            let mut acc = 0.0_f32;
            for k in 0..half { acc += fb[m * half + k] * pow[k]; }
            out[f * cfg.n_mels + m] = (acc + 1e-10).ln();
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hann_window_starts_and_ends_at_zero() {
        let w = hann_window(8);
        assert!(w[0].abs() < 1e-6);
        assert!(w[7].abs() < 1e-6);
        assert!(w[4] > 0.9); // mid-window peaks near 1
    }

    #[test]
    fn fft_of_dc_signal_concentrates_at_bin_zero() {
        // x[n] = 1 for all n → X[0] = N, rest = 0.
        let n = 8;
        let mut re = vec![1.0_f32; n];
        let mut im = vec![0.0_f32; n];
        fft(&mut re, &mut im);
        assert!((re[0] - n as f32).abs() < 1e-4);
        for k in 1..n {
            assert!(re[k].abs() < 1e-4 && im[k].abs() < 1e-4,
                "bin {k}: re={} im={}", re[k], im[k]);
        }
    }

    #[test]
    fn fft_of_single_sine_concentrates_at_its_bin() {
        let n = 16;
        let k0 = 3; // frequency bin
        let mut re = vec![0.0_f32; n];
        let mut im = vec![0.0_f32; n];
        for i in 0..n {
            re[i] = (2.0 * core::f32::consts::PI * (k0 as f32) * (i as f32) / (n as f32)).cos();
        }
        fft(&mut re, &mut im);
        let mag: Vec<f32> = re.iter().zip(&im).map(|(r, i)| (r * r + i * i).sqrt()).collect();
        // Peak at k0 and at n-k0 (Hermitian symmetry).
        let max = mag.iter().cloned().fold(0.0_f32, f32::max);
        assert!((mag[k0] - max).abs() < 1e-2 || (mag[n - k0] - max).abs() < 1e-2);
    }

    #[test]
    fn hz_mel_round_trip() {
        for hz in [0.0_f32, 100.0, 1000.0, 4000.0, 8000.0] {
            let r = mel_to_hz(hz_to_mel(hz));
            assert!((r - hz).abs() < 0.5, "hz={hz} round={r}");
        }
    }

    #[test]
    fn log_mel_spectrogram_produces_correct_shape() {
        let cfg = AudioConfig {
            sample_rate: 8000,
            win_size: 16,
            hop_size: 8,
            n_fft: 16,
            n_mels: 4,
            mel_low_hz: 0.0,
            mel_high_hz: 4000.0,
        };
        let pcm = vec![0.5_f32; 64];
        let spec = log_mel_spectrogram(&pcm, &cfg);
        // num_frames = (64 - 16) / 8 + 1 = 7
        assert_eq!(spec.len(), 7 * 4);
    }
}
