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

Every header below must be **present**. Dropping `x-opencode-client` or
`x-opencode-project` drove the pass rate to **0/4** in a repeated-trial probe,
so those two are hard requirements, not decoration. The four session headers
must exist too, but are *not* required to carry equal values — mixing them
still passes; the official CLI sends one value everywhere and so do we.

| Header | Value | Required? |
| --- | --- | --- |
| `Authorization` | `Bearer public` | yes |
| `User-Agent` | `opencode/prod/1.19.3/cli` | yes |
| `x-opencode-client` | `cli` | **yes (0/4 without)** |
| `x-opencode-project` | `global` | **yes (0/4 without)** |
| `x-opencode-session` | session id | yes (existence) |
| `x-opencode-session-id` | session id | yes (existence) |
| `x-session-affinity` | session id | yes (existence) |
| `x-session-id` | session id | yes (existence) |
| `Accept` | `text/event-stream` | yes |

`x-opencode-request` must **not** be sent. (Older write-ups list it as
required; the current client dropped it, and sending it is harmless but
pointless.)

### 403 is overloaded — measure a PASS RATE, never a single shot

This is the most important operational fact about the endpoint, and it was
learned the hard way. A single request proves nothing about the gate, because
the same request alternates between success and `403 FreeTierError`:

| Variant | Pass rate (4 trials) |
| --- | --- |
| full identity + gate tools | **3/4** |
| no `tools` at all | **0/4** |
| gate tools, `x-opencode-project` removed | **0/4** |
| gate tools, `x-opencode-client` removed | **0/4** |

So `403 FreeTierError` means one of two different things: *your request
violates the contract* (deterministic, 0/4) or *you are being throttled*
(intermittent). Sending the same payload twice and observing 403 then 200 does
**not** mean "sometimes it is allowed" — it means one of the two trials was
throttled.

Two consequences:

1. **Any probe must repeat.** Earlier editions of this document recorded
   single-shot results (including one that blamed a missing
   `x-opencode-project` without noticing the same request also passed 2/4 in a
   later run). Single-shot 403s are not evidence. The pass-rate table above is
   the corrected reading.
2. **The client must not treat 403 as a dead end.** Mapping it to a bad-API-key
   error is actively harmful for a keyless provider — there is no key for the
   user to fix, and the request would have succeeded on retry. The provider
   classifies a 403-with-valid-identity as retryable instead.

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
| Gate tools | `shell`, `glob`, `grep`, `read` must all be declared — **0/4 without them**. |
| Schema depth | Ambiguous, and deliberately not relied upon: a mixed-shape probe (bare names, empty `properties`, real schemas) could not separate the variants, because 403 is also what a throttle returns. We ship full real schemas, which is what the official client declares, so the uncertainty is moot. |
| Placement | When the caller passed no tools at all, the gate set is the whole `tools` array. When the caller passed tools, the gate set is appended to them; the app's own tools are preserved. |

> Earlier editions of this file asserted flatly that an empty-`properties`
> schema is rejected. That claim came from a single-shot probe and does **not**
> survive repetition: in a spaced re-run, four gate tools with *empty* schemas
> returned 200 and the same request with full schemas returned 403 moments
> later. The variable was the throttle, not the schema. Recorded here as
> unresolved rather than stated as fact, per the pass-rate rule above.

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

## Model catalogue — discovered, unioned with a curated floor

`GET /inference/v1/models` returns ~85 entries, but only a handful are served by
the free tier. The list carries **no machine-readable free flag** — `owned_by`
is `"opencode"` for every entry — and names only partly indicate it:

| Id | Free? | Why it's a trap |
| --- | --- | --- |
| `deepseek-v4.1-flash-free` | yes | suffix is honest |
| `jev-1.13-free` | **no** | suffix lies — always `ModelProtocolUnsupported` |
| `big-pickle` | **yes** | no suffix at all |
| `gpt-5.4-pro`, `claude-opus-4-6`, … | no | ~76 paid ids |

### The discriminator

A probe found the endpoint *does* distinguish them, just not in the listing: a
request for a **paid** model answers **401** (an account is required), while a
free model is served without one. `OpenCodeFreeModelsApi.classifyProbe` encodes
exactly that, and treats **403 as UNKNOWN** rather than as a verdict — because
403 is the overloaded code described above, and reading it as "this model is
unavailable" would drop a good model every time the endpoint was busy.

### What ships

- `OpenCodeFree.CATALOG` — a small curated list that is verified-good. It is a
  **floor**, not the whole list.
- `OpenCodeFreeModelsApi.fetchFreeCandidates()` — fetches the live list and
  keeps `-free`-shaped ids minus a tiny denylist of ids *observed* to fail
  (`KNOWN_UNUSABLE`).
- **The two are unioned, never replaced.** Discovery is name-based and therefore
  incomplete — it cannot see `big-pickle` — so a replace-only merge would
  silently drop a working model the user could previously pick. Curated first
  (better display names), discovered added on top, deduped by id.
- If discovery fails or comes back empty, the curated list is kept rather than
  emptying the picker.
- The result is cached per instance as ids only; display name and limits are
  derived on read, so there is no second copy to drift.

`jev-1.13-free` is excluded rather than offered and then failing.

## What is NOT discoverable

Worth recording so nobody re-hunts for it:

| Looked for | Result |
| --- | --- |
| `/inference/v1/config`, `/inference/v1/free-tier`, `/inference/v1/gate` | 404 |
| `/.well-known/opencode` | 404 |
| a free flag in `/models` | absent (`owned_by` is uniform) |
| a tool requirement in the error body | absent — every malformed case returns the *same* generic `FreeTierError`, so the endpoint never names the tools it wants |
| the official npm package as a source of gate constants | not useful — `opencode-ai` publishes only 4 files (7.8 KB: a stub binary, `postinstall.mjs`, `package.json`, LICENSE); the logic lives in platform binaries, not in published JS |

So the gate contract cannot be read from the server; it must be established by
probing and pinned, with the version floor self-healed at runtime.

## Rate limiting

Limits are enforced per session id, which is why one session id is minted per
provider instance and reused for every request rather than rotated per call:
rotating buys a marginally different fingerprint at the cost of immediate 429s.
Expect occasional 429s regardless; they are load-related, not a contract
violation.

⚠️ Because throttling surfaces as `403 FreeTierError` rather than 429, a
throttled request is indistinguishable from a contract violation **by body**.
The client separates them by checking that its own identity block is complete
(`OpenCodeFree.identityLooksValid()`) and treats a 403-with-valid-identity as
retryable. See the pass-rate table above.

