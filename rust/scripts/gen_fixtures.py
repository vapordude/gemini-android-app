#!/usr/bin/env python3
"""Generate math-parity fixtures from the HF Gemma 4 E2B reference model.

This script is intended to run OFF-DEVICE on a machine with the Hugging
Face transformers stack installed. It loads the abliterated Gemma 4 E2B
model, runs one forward pass on a deterministic prompt, captures
intermediate tensors at each layer site with forward hooks, and writes
them as `safetensors` files under `rust/parity-fixtures/`.

The Rust parity harness (gemma4-ops::parity) then loads these fixtures
and asserts that each Rust kernel produces output within tolerance.

Run:
    cd rust
    uv run scripts/gen_fixtures.py
or:
    python3 scripts/gen_fixtures.py

Requirements (install via uv or pip):
    transformers >= 5.5  (Gemma 4 support landed there)
    torch
    safetensors

The script is intentionally dependency-light beyond those three. Do not
add new imports without a good reason -- keeping the script auditable.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent.parent / "parity-fixtures"
MODEL_ID = "wangzhang/gemma-4-E2B-it-abliterated"  # matches the HF URL the user pinned
PROMPT = "Hello, world!"
SEED = 0


def main() -> int:
    try:
        import torch
        from transformers import AutoModelForImageTextToText, AutoTokenizer
        from safetensors.torch import save_file
    except ImportError as exc:
        print(f"[fixtures] missing dependency: {exc}", file=sys.stderr)
        print("[fixtures] install with: pip install 'transformers>=5.5' torch safetensors",
              file=sys.stderr)
        return 1

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    torch.manual_seed(SEED)

    tok = AutoTokenizer.from_pretrained(MODEL_ID)
    model = AutoModelForImageTextToText.from_pretrained(
        MODEL_ID, dtype=torch.float32, device_map="cpu",
    )
    model.eval()

    enc = tok(PROMPT, return_tensors="pt")
    input_ids = enc["input_ids"]

    captures: dict[str, "torch.Tensor"] = {}

    # ---- Register forward hooks at the parity sites the Rust pipeline
    # produces. Each site name matches the convention used in the Rust
    # tests: `L{layer}.{site}`. Sites:
    #   pre_attn_norm, post_attn, post_attn_norm, residual_attn,
    #   pre_ffn_norm, swiglu, post_ffn_norm, residual_ffn, ple

    decoder_layers = getattr(getattr(model, "model", model), "layers", None)
    if decoder_layers is None:
        print("[fixtures] couldn't locate decoder layers -- model structure changed?",
              file=sys.stderr)
        return 2

    def hook(name: str):
        def _h(_mod, _input, output):
            t = output[0] if isinstance(output, tuple) else output
            captures[name] = t.detach().to(torch.float32).cpu()
        return _h

    for i, layer in enumerate(decoder_layers):
        # Best-effort attribute names. Real Gemma 4 may differ; adjust the
        # right-hand side once the user pastes the live config.
        for site_attr, site_name in [
            ("input_layernorm", "pre_attn_norm"),
            ("post_attention_layernorm", "post_attn_norm"),
            ("pre_feedforward_layernorm", "pre_ffn_norm"),
            ("post_feedforward_layernorm", "post_ffn_norm"),
            ("self_attn", "attn"),
            ("mlp", "ffn"),
        ]:
            mod = getattr(layer, site_attr, None)
            if mod is None:
                continue
            mod.register_forward_hook(hook(f"L{i:02d}.{site_name}"))

    with torch.no_grad():
        _ = model(input_ids=input_ids)

    # Save inputs separately so the Rust side can reproduce the trace.
    inputs_path = OUT_DIR / "inputs.safetensors"
    save_file(
        {"input_ids": input_ids.to(torch.int64).cpu().contiguous()},
        str(inputs_path),
    )
    print(f"[fixtures] wrote {inputs_path}")

    # One file per layer site -- keeps the test harness's load O(1) per kernel.
    written = 0
    for name, tensor in captures.items():
        path = OUT_DIR / f"{name}.safetensors"
        save_file({name: tensor.contiguous()}, str(path))
        written += 1
    print(f"[fixtures] wrote {written} layer-site tensors under {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
