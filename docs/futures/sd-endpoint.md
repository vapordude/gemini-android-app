# Stable Diffusion endpoint — next-sesh note

> Status: **planning**. Captured for next session. Sits behind the
> warnings cleanup + OAuth-images PR in the queue.

## What it is

A configurable HTTP image-generation endpoint that pairs with Imagen
and Nano Banana so users can route to any of:

- **Imagen** (Google, cloud, API-key today / OAuth after the next PR)
- **Nano Banana** = Gemini 2.5 Flash Image (Google, cloud, inline in chat)
- **Stable Diffusion endpoint** — user-configured HTTP, brings their
  own model server

Drop-in support unlocks: ComfyUI / SD.next / AUTOMATIC1111 / Forge /
remote Replicate-style endpoints / Pi 5-hosted local SD / friend-runs-
a-server-in-the-garage setups. Same approval / persistence / chat-
bubble path as the existing image tools — only the wire format
changes.

## Why beyond images

The user's framing — *"generating meshes graphics and whatever"* — is
the right scope. A configurable image endpoint is one slice of a
broader "remote inference endpoints" surface that also covers:

- **Stable Diffusion / SDXL / Flux** for static images
- **Stable Video Diffusion / AnimateDiff** for short video
- **TripoSR / Stable Fast 3D / SF3D / TRELLIS** for mesh generation
  from an image or prompt
- **MusicGen / AudioLDM** for sound
- **Tortoise / XTTS** for voice

The endpoint config schema is the same for all of them — what differs
is the request/response shape per backend. Land SD first; the rest
follow the same pattern.

## Endpoint config schema

Shares the **Endpoint** primitive flagged in `docs/futures/swarm-workers.md`
and `docs/futures/roleplay-rooms.md`. Adds a new `EndpointPurpose` enum:

```kotlin
data class GenerativeEndpoint(
    val id: String,
    val displayName: String,
    val kind: EndpointKind,         // SdAutomatic1111, SdComfy, Replicate, …
    val purpose: EndpointPurpose,   // Image, Video, Mesh3d, Audio, Voice
    val baseUrl: String,
    val authToken: String?,
    val defaultModel: String?,      // e.g. "sdxl_turbo.safetensors"
    val defaultParams: Map<String, Any?>,  // steps, cfg, sampler, seed…
)

enum class EndpointKind {
    // Image
    SdAutomatic1111,    // AUTOMATIC1111 sdapi/v1
    SdComfy,            // ComfyUI /prompt + websocket
    SdForge,            // Forge fork (≈ A1111 API)
    Replicate,          // replicate.com unified API
    // Video / mesh / audio — same shape, different paths
    SvdEndpoint,
    Tripo3d,
    Replicate3d,
    // Future
    Custom,             // user-supplied request template
}

enum class EndpointPurpose { Image, Video, Mesh3d, Audio, Voice }
```

Persisted alongside MCP servers + chat endpoints under
`filesDir/endpoints.json`.

## Tool surface (sibling to existing image tools)

Three new tools — one per purpose makes more sense than one fan-out
tool because each has different parameters and result shapes:

| Tool | Backed by | Returns |
|---|---|---|
| `generate_image_via(endpoint_id, prompt, …)` | image endpoints | PNG to attachments dir |
| `generate_video_via(endpoint_id, prompt, …)` | video endpoints | MP4 to attachments dir |
| `generate_mesh_via(endpoint_id, prompt_or_image, …)` | 3D endpoints | GLB to attachments dir |

Plus a discovery tool:

| `list_generative_endpoints(purpose?)` | returns configured endpoints filtered by purpose |

All three result in attachments saved to `filesDir/attachments/` and
rendered as thumbnails (image), inline preview (video), or as a
download link with a "view in compatible app" hint (mesh — Android
doesn't have a built-in GLB viewer).

## Settings surface

A "Generative endpoints" drawer destination (or a Settings → Endpoints
section) listing configured endpoints, + Add, + Test, + Edit, + Delete.
Test probes the endpoint with a tiny known prompt and a 5-second
timeout; surfaces status pip (online / unauth / wrong-version / down).

## Approval / safety

- Generative endpoints default `destructive=true` (network egress to
  a third party, cost implications)
- Default-redact workspace content from prompts unless the user opts
  in per endpoint (consistent with the workspace-private-folders
  feature flagged in the sandbox-mode discussion)
- Output saves to attachments + chat bubble — no auto-execution

## Phasing

| Phase | Scope |
|---|---|
| **v1** | EndpointConfig persistence + Settings UI for image-only endpoints + `generate_image_via` tool + AUTOMATIC1111 + ComfyUI backends |
| **v2** | Replicate backend + endpoint health-pinger + workspace-private redaction |
| **v3** | Video + mesh + audio + voice purposes |
| **v4** | Per-project default endpoint (project → "use SDXL Turbo on my Pi 5 by default") |

## What this unlocks for the family

- **Kaimahi** ships generative-endpoint *configuration* free.
- **Crimson** (Pi 5) becomes a natural SD/SVD/SF3D *host* — pair your
  phone with your Pi as a generation server over Tailscale.
- **Lux** (companion) uses voice endpoints for character audio in
  roleplay rooms.

Same Endpoint primitive across swarm workers, roleplay rooms, and
generative endpoints means the user configures their AI infrastructure
once and three features benefit.
