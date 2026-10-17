# OpenCode free tier — wire contract

Notes on the keyless OpenCode endpoint that backs `ProviderType.openCodeFree`.
Everything below was established by **probing the live endpoint**, not by reading
a reference implementation. Several rules that circulate in third-party
write-ups (and in older scripts) turned out to be stale; the tables record what
the endpoint actually does, so future changes have a baseline to diff against.

Endpoint: `https://opencode.ai/inference/v1/chat/completions`
Auth: `Authorization: Bearer public` — there is no account and no key.

## Why this is a separate provider instead of a custom OpenAI endpoint

The endpoint is OpenAI chat-completions *shaped*, so it could in principle be
reached through the "OpenAI / Compatible API" provider with a custom base URL.
It is not, because the upstream validates the request beyond auth:

1. **Identity headers** — a client that does not look like the official CLI is
   rejected outright (403).
2. **Body gate** — the request must declare four specific tools and be streamed.

Neither is expressible through the generic provider's config surface, so the
contract lives in code (`provider/opencode/OpenCodeFree.kt`), and the provider
class (`OpenCodeFreeProvider.kt`) is a thin `OpenAIProvider` subclass that
supplies it via two extension hooks. No TLS fingerprinting is involved: plain
`okhttp` is accepted, verified by issuing identical requests with four
different client shapes (chrome / firefox / safari / chrome110 TLS stacks) and
receiving 200 from all four.

## Identity headers

All four session headers must be **present**. They are *not* required to carry
equal values — mixing them still returns 200 — but the official CLI sends one
value everywhere, and so do we.

| Header | Value |
| --- | --- |
| `Authorization` | `Bearer public` |
| `User-Agent` | `opencode/prod/2.0.29/cli` |
| `x-opencode-client` | `cli` |
| `x-opencode-project` | `global` |
| `x-opencode-session` | session id |
| `x-opencode-session-id` | session id |
| `x-session-affinity` | session id |
| `x-session-id` | session id |
| `Accept` | `text/event-stream` |

`x-opencode-request` must **not** be sent. (Older write-ups list it as
required; the current client dropped it, and sending it is harmless but
pointless.)

### User-Agent is version-gated — by a FLOOR, with no upper bound

The UA is parsed, and the version inside it is checked against a floor. The
floor is **not** pinned to a major line: the endpoint accepts anything at or
above it, including versions far beyond what has been published.

| UA | Result |
| --- | --- |
| `opencode/1.19.0` | 200 |
| `opencode/prod/2.0.29/cli` | 200 |
| `opencode/prod/999.0.0/cli` | **200 — no upper bound** |
| `opencode/1.18.0` | 200 (exactly at the floor) |
| `opencode/1.0.0` | **426 Upgrade Required** |
| `opencode/0.0.1` | **426 Upgrade Required** |
| `OpenCode/1.19.0` | 200 (prefix match is case-insensitive) |
| `opencode` bare | **403 `FreeTierError`** |
| `opencode/x.y.z` | **403** (version not parseable) |
| `curl/8.5.0` | **403** (no `opencode` prefix) |

The 426 body names the floor it wants, verbatim:

```json
{"type":"error","error":{"type":"UpgradeRequired",
 "message":"OpenCode 1.18.0 or newer is required to use the free tier"}}
```

> **Correction (superseded an earlier claim in this doc).** An earlier revision
> of this file asserted the floor was **v2** (`2.0.0`) and concluded that npm
> could not be used as a version source. Both were wrong: the observed floor is
> **1.18.0**, and while npm's `latest` (1.19.3) is *below* old `2.0.29` pins it
> is perfectly acceptable. Re-probing is what caught this; the tables above
> are the re-probed values.

Because the gate is a floor with no ceiling, "use the newest published
version" is always a safe answer — which is what makes the self-healing in
`OpenCodeFree` sound. Three layers, highest wins:

1. **Server demand** — a 426 body states the floor. `adoptRequiredVersion`
   parses it, validates it against `MIN_SUPPORTED_CLIENT_VERSION`, and steps
   one patch *above* it (`1.18.0` → `1.18.1`) so the value is strictly greater
   than the demand rather than exactly at it. This costs no network I/O — the
   server hands over the answer in the error body — so the retry that follows
   immediately is already above the new floor.
2. **Registry advertisement** — `https://registry.npmjs.org/opencode-ai/latest`
   is probed once per process, off the request path. Use `/latest`, **not** the
   full packument: `/latest` is ~2 KB, the packument is ~25 MB and contains
   12k+ versions.
3. **Pinned default** — `DEFAULT_CLIENT_VERSION` (1.19.3). Works on its own;
   1 and 2 are what keep it working after the floor moves.

Note that npm's `stable`/`latest` channel tracks the **1.x** line (12,253
published versions, none ≥ 2.0.0 when this was written), so a naive "use npm
`latest`" would have produced a version that an old v2-floor assumption
rejected. Under the real floor it is fine — but the point stands that the
version must be validated against a *known-accepted* floor, never assumed.

### Session id prefix is decoded

A session id is `ses_` + 12 hex + 14 base62. The 12-hex prefix is not opaque —
the upstream decodes it back into an identifier timestamp and rejects ids whose
prefix does not look like one (a random 12-hex prefix fails with
`FreeTierError`). Sessions use the *descending* variant, i.e. the timestamp is
bitwise-inverted before encoding.

## Body gate

| Rule | Detail |
| --- | --- |
| `stream` | Must be `true`. The endpoint forces streaming. |
| Gate tools | `shell`, `glob`, `grep`, `read` must all be declared. |
| Empty schemas | A gate tool declared with no `properties` does **not** satisfy the requirement — it must carry a real schema. |
| Placement | When the caller passed no tools at all, the gate set is the whole `tools` array. When the caller passed tools, the gate set is appended to them; the app's own tools are preserved. |

Because the gate tools are advertised to the model, they can be *called* by a
model that does not realize they are inert. When we inject them into a request
that already has a system message, a one-line note is appended to that message
explaining that the four names are placeholders. If there is no system message
there is nowhere to put the note — the tools are still advertised, which is
accepted, but the note is skipped.

## Accepted but unconstrained

Probed and confirmed to return 200; none of these need special handling:

- `max_tokens` and `max_completion_tokens` — both spellings work.
- `stream_options: {include_usage: true}` — usage is reported.
- `tool_choice: "auto"` / `"none"`.
- Extra client tools alongside the gate set.

## Model catalogue

`GET /inference/v1/models` returns ~85 entries, but only a handful are served
by the free tier — the rest are paid and 403 on send. The provider therefore
ships a **static** catalogue (`OpenCodeFree.CATALOG`) instead of fetching and
filtering, so the picker can never offer a model that will fail.

`jev-1.13-free` appears in the live list and carries the `-free` suffix, but
answers `ModelProtocolUnsupported` on every request; it is deliberately
excluded rather than offered and then failing.

## Rate limiting

Limits are enforced per session id, which is why one session id is minted per
provider instance and reused for every request rather than rotated per call:
rotating buys a marginally different fingerprint at the cost of immediate 429s.
Expect occasional 429s regardless; they are load-related, not a contract
violation.
