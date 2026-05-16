# Parity fixtures

This directory holds PyTorch-traced reference tensors that pin the Rust
inference kernels to numerical agreement with the HF reference impl.

## Generating

```bash
cd rust
python3 scripts/gen_fixtures.py
```

Requirements: `transformers >= 5.5`, `torch`, `safetensors`.

The script:

1. Loads `wangzhang/gemma-4-E2B-it-abliterated` from Hugging Face (the model
   the user pinned).
2. Tokenizes the prompt `"Hello, world!"` with `torch.manual_seed(0)`.
3. Registers forward hooks on every decoder-layer sublayer (input
   LayerNorm, post-attention LayerNorm, pre-FFN LayerNorm, post-FFN
   LayerNorm, attention output, MLP output).
4. Runs one forward pass.
5. Dumps each captured tensor as `L{layer}.{site}.safetensors`, plus
   `inputs.safetensors` with the input token IDs.

Fixtures are intentionally large (BF16 hidden state × 35 layers × N
sites). Don't commit them — they're regenerated on demand. They live
here for the Rust test harness to find them at test time.

## Using

The Rust harness loads them via:

```rust
let bytes = std::fs::read("../parity-fixtures/L00.pre_attn_norm.safetensors")?;
let expected = gemma4_ops::parity::load_f32(&bytes, "L00.pre_attn_norm")?;
gemma4_ops::parity::assert_close(&actual, &expected, TOL_F32, "L00.pre_attn_norm");
```

If you change a kernel and a parity test starts failing, the message
includes the worst-case index and absolute magnitude so you can pinpoint
the regression immediately.
