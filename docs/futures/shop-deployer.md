# Shop deployer — agent-emitted sovereign storefronts

> Status: **planning**. Captured from chat (Qwen3.6 white paper + Dan).
> Sits after the v0.3.0 cut. The phone does NOT host shops; it
> orchestrates a deployer that lives elsewhere (Termux on the S25,
> a laptop, or a free-tier VM).

## What it is

A two-sided system that lets the local agent design and deploy a real,
NZD-settling, accessibility-compliant storefront on demand:

- **Agent end (Android / `agent-bridge` / new `shop-helper` module).**
  A design-skills helper the agent calls when asked to build a shop.
  Owns the somatic-first design palette (WCAG, reduced-motion,
  keyboard-first), the SPDX licence header policy, the NZD routing
  defaults, and a `ShopTemplate` library — header / hero / product-card
  / cart-stub / checkout-CTA — so the agent fills in product data
  instead of re-deriving the design system per turn. Output is an
  **AEMS** JSON struct (Agent-Emitted Manifest Schema) validated
  against `agent-bridge`'s schema before it leaves the device.
- **Deployer end (`native/local-server` extension or sibling
  `native/shop-deployer` crate, surfaced through emdash-rs).** An axum
  HTTP service that accepts a signed AEMS struct, renders static
  templates (Tera), injects the accessibility CSS + SPDX header,
  generates a Caddy reverse-proxy config, updates DuckDNS, registers a
  Stripe Express + PayPal NZD webhook handler, and schedules a TTL
  auto-prune. Webhook → emdash-rs generates the artefact → HMAC-signed
  time-limited download link.

The two halves talk over the existing MCP transport: the agent
publishes an AEMS struct; the deployer's MCP server exposes
`deploy_shop`, `update_listing`, `prune_shop`, `list_shops` tools.

## Why bother

1. **First real-world test of the agent loop end-to-end.** Designing
   a shop site exercises every layer we've built — dynamic screens,
   tool calls, MCP, attribution metadata, multi-step planning — and
   produces a thing a stranger can actually buy from.
2. **Zero upfront cost matches the project's posture.** No SaaS
   subscriptions, no platform fees, NZD direct settlement, Caddy +
   DuckDNS + free-tier compute. Aligns with Kaimahi's "local AI
   worker" framing — the agent doing the boring infra work for the
   operator.
3. **Snappy authoring on the S25.** Prewarming the `ShopTemplate`
   shells in `shop-helper` means the agent's hotswap (just the
   product payload) is sub-100ms in the preview pane, instead of the
   multi-second wait for a from-scratch `CreateScreenTool` call.

## Shape of the on-device piece

```
ShopSiteHelper
├── Design palette        // Compose fragments: header, hero, card, checkout
├── A11y defaults         // contrast, motion, focus-ring, hit targets
├── SPDX policy           // attribution headers per artefact source
├── Payment routing       // NZD → Stripe Express / PayPal fallback
├── AEMS emitter          // typed struct → JSON, schema-validated
└── DeviceFrame(S25Ultra | Laptop)  // preview chrome around DynamicScreen
```

The agent calls `ShopSiteHelper.scaffold(intent)` → gets a populated
AEMS struct → fills in product copy / images / price → `helper.emit()`
→ MCP `deploy_shop` tool ships it to the deployer.

The `DeviceFrame` composable wraps the existing `DynamicScreen` host
at a fixed dp box (S25 Ultra ≈ 412×915 dp, Laptop ≈ 1280×800 dp) with
a thin bezel chrome and a toggle in the host. Lets the operator
visually evaluate the agent's output on-device before the deployer
publishes.

## Shape of the deployer

```
native/shop-deployer/         (or local-server extension)
├── schema/aems.rs            // serde structs + JSON Schema export
├── render/templates/         // Tera: index.html, product.html, checkout.html
├── render/a11y.css           // injected at render time
├── render/spdx.rs            // attribution header injection
├── routes/deploy.rs          // POST /deploy {AEMS} → site path
├── routes/webhook_stripe.rs  // HMAC verify → trigger gen
├── routes/webhook_paypal.rs  // ditto, NZD
├── caddy/config.rs           // emit reverse-proxy snippet
├── duckdns/updater.rs        // systemd-friendly IP refresh
├── ttl/pruner.rs             // delete expired shops on schedule
└── mcp/server.rs             // expose deploy_shop / update_listing / etc.
```

## Open questions (deferred)

- **What do we sell?** The whole stack works equally well for AI-art
  prints, sovereign-tier consulting hours, te reo learning packs, or
  Crimson licences. Pick after the deployer can render *anything*
  end-to-end; don't pre-bias the schema.
- **Where does the deployer live?** Termux on the S25 (zero
  additional hardware), laptop (snappier, less battery), or a
  free-tier VM (always-on, but adds a third party). Architecture
  doesn't care; pick by operational preference.
- **Edge sync.** If the deployer is on Termux and the phone goes
  offline, do shops keep serving? Probably yes via Caddy's local
  cache + DuckDNS, but this needs measurement.
- **Critic step.** Once swarm-workers (`docs/futures/swarm-workers.md`)
  lands, a critic worker can audit AEMS structs before deploy —
  contrast ratio, missing alt text, dangling SPDX, payment-route
  mismatch.

## Sequencing

1. Land v0.3.0 (libkaimahi_native ships + Cathedral rebrand + local-
   first picker).
2. Spike `ShopSiteHelper` in `agent-bridge` with the palette + AEMS
   emitter — no deployer yet, render straight to `DynamicScreen` for
   preview only.
3. Stand up `native/shop-deployer` as an axum service behind MCP.
   Single-page artefact, single payment route.
4. Wire DuckDNS + TTL + Caddy + the second payment route.
5. First real shop deploy. Measure: prewarm-to-preview latency, swap
   latency, time-from-AEMS-to-published-URL.
