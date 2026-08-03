# OpenMinis — Android

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20arm64-brightgreen.svg)](#install)
[![Build](https://github.com/logicflow-GYW/OpenMinis/actions/workflows/build-apk.yml/badge.svg)](https://github.com/logicflow-GYW/OpenMinis/actions/workflows/build-apk.yml)

**Your private, on-device AI agent.**

An **Android-only fork** of [OpenMinis](https://github.com/OpenMinis/OpenMinis)
that builds a working APK on GitHub Actions and publishes it automatically.

OpenMinis brings leading models — Claude, GPT, Gemini and more — into a native
mobile experience, and gives them a real computer to work with: a full Linux
shell running on your device, browser automation, extensible skills, persistent
memory, and deep system integration.

---

## Install

**→ [Download the latest APK](https://github.com/logicflow-GYW/OpenMinis/releases/tag/android-latest)**

Every push to `main` builds a release APK and republishes it to that link, so
the URL always points at the newest build. Requirements:

- **arm64-v8a** device (any modern phone), **Android 8.0+**
- Enable "install from unknown sources" when your device prompts

Builds are signed with a fixed key, so a new APK installs **over** the previous
one — your data and settings are preserved.

```
SHA-256  FC:0C:40:0D:B7:7E:C1:81:A3:35:18:C2:E8:13:6A:AE
         1A:3F:6C:79:4A:1A:A7:9F:DB:67:63:8F:C6:B1:61:13
```

Verify a download with `python3 scripts/apk_cert_sha256.py <apk>`. Note this
differs from the official build's key, so if you currently have the official
APK installed you must uninstall it first.

---

## What this fork changes

This started as a build-only fork, but it now also carries a small set of
Android-specific product changes that are not present upstream.

### App changes

- **Complete local backup and restore.** Settings → Storage → Backup & Restore
  exports a portable JSON file and imports it on another installation. It
  covers provider/model configuration and groups, optional API keys,
  environment variables, app/agent/chat defaults, Soul, complete skills
  (SKILL.md plus bundled scripts, references and assets), persistent memory,
  MCP server configuration, and chat history (text-only, last 90 days by
  default, window adjustable in Backup settings).
- **Honest exclusions.** Chat history is carried as text only: media
  (images/videos) and attached files are dropped, and only the last N days of
  activity are included (0–365, default 90; 0 disables chat history). 
  Mounted-folder permissions cannot be transferred between Android devices,
  and MCP OAuth client secrets/tokens are never exported — OAuth-backed MCP
  servers must be re-authorized after restore.
- **Chat UI refinements.** Message links can focus and highlight a specific
  message; navigation titles are left-aligned; the active model selector lives
  in the composer; attachment and command actions are arranged more compactly.
- **Left-swipe chat history drawer.** The chat screen opens a slide-out
  conversation list from the left edge (or via the hamburger button), so you
  can switch between past conversations — or start a new one — without leaving
  the current chat. The drawer mirrors the session list: same grouping,
  category icons and relative timestamps, current chat highlighted, and
  long-press to delete a conversation.
- **UX polish.** Entering the app no longer pops the keyboard — the composer
  only focuses when you tap it. Tool-result thumbnail previews in the
  composer are off by default (toggle in Settings → Appearance). The chat
  "…" menu exports the current conversation (JSON or plain text, listed
  between Slash Commands and Token Usage) and no longer lists Clear Chat,
  which duplicated New Chat and could strand an empty ghost conversation.
- **Simpler composer.** The dedicated voice-chat shortcut and its inline UI
  have been removed. Android's agent-facing speech tools are unaffected.
- **Settings consistency fixes.** Restored preferences refresh the live
  settings UI, and previously disconnected/missing settings keys are now
  registered and included in backups.

### Build and release changes

- **proot is built from source.** The sandbox engine comes from the
  `deps/proot` submodule + `deps/build_proot.sh` + vendored `deps/talloc`,
  compiled with NDK r28 in CI — no committed binary blobs, fully reproducible.
- **Other native libs stay vendored.** `libpty_bridge.so`,
  `libminis_crash_handler.so` and `libjieba_jni.so` are committed as-is.
- **Backup tests run in CI.** The backup payload tests run before the APK
  build.
- **iOS sources removed.** `src/ios/` is gone; this tree is Android only.
- **Automatic releases.** Successful builds publish the APK to the
  `android-latest` release.


### Why build proot from source?

The sandbox engine `libproot.so` needs upstream's Android 10+ W^X bypass
patches. Building it through AGP's CMake block produces a binary that compiles
fine and then fails at runtime with `execve("/bin/sh"): Permission denied` —
the terminal never opens. This fork therefore builds it with
`deps/build_proot.sh` (the upstream-supported path — same source, same NDK
toolchain the official binary is built with) instead of CMake.
`externalNativeBuild` stays disabled so AGP never overwrites the vendored
pty_bridge / crash_handler / jieba libraries with unpatched CI-built copies.

**Trade-off:** edits under `src/android/app/src/main/cpp/` are not compiled —
only `deps/proot` is built, via `build_proot.sh`. Changing the other native
code means restoring the CMake block and installing the NDK in CI. Kotlin, UI,
prompts and model integrations are unaffected — build normally.

---

## What it does

| | |
|---|---|
| **Bring your own model** | Claude, GPT, Gemini and other providers, via your own API keys or account sign-in. |
| **A real Linux shell** | A sandboxed Alpine Linux environment runs on-device — the agent can install packages, run scripts, and work with real files. |
| **Device integration** | Calendar, Contacts, Clipboard, Location, Media, Alarms, Notifications and more, exposed to the agent as tools. |
| **Browser automation** | The agent can browse and interact with the web on your behalf. |
| **Skills & memory** | Extensible skills plus persistent memory across sessions. Complete skill bundles and memory files are included in local backups. |
| **Local backup & restore** | Export configuration, credentials (optional), skills, memory, MCP servers and chat history (text, last N days) to one portable JSON file. |
| **Workspaces** | Organise work into separate contexts, addressable via `minis://workspace/`. |
| **Native offloads** | Heavy or platform-specific work is handed to native code instead of the sandbox. |

**→ [OpenMinis/MinisSkills](https://github.com/OpenMinis/MinisSkills)** — ready-made
skills. Skills built for Claude, Codex, OpenClaw or Hermes Agent generally run
in Minis as-is.

**→ [OpenMinis/AwesomeMinis](https://github.com/OpenMinis/AwesomeMinis)** — a
curated collection of use cases and workflows.

---

## Building locally

```sh
git clone --recurse-submodules https://github.com/logicflow-GYW/OpenMinis.git
cd OpenMinis/src/android
../../deps/build_proot.sh        # build the proot sandbox engine from source
./gradlew assembleRelease
```

Needs **JDK 17**, the Android SDK (compileSdk 36) and **NDK r28** — the latter
for `deps/build_proot.sh`, which compiles the proot sandbox engine from the
`deps/proot` submodule (the other native libs are vendored in the tree). The APK
lands in `app/build/outputs/apk/release/`.

Local builds are signed with your own `~/.android/debug.keystore`, so they will
not install over a CI build. To match CI, put the same keystore there.

See [BUILDING.md](BUILDING.md) for toolchain details and troubleshooting.

---

## Keeping up with upstream

Upstream is a one-way mirror that does not accept pull requests, and this fork
has diverged in a handful of files. Syncing is possible but has an order of
operations — in particular, the vendored pty_bridge / crash_handler / jieba
libraries must be refreshed whenever upstream's Kotlin changes, or the app
breaks at runtime. proot is **not** vendored any more: it is built from source
in CI via `deps/build_proot.sh`, so the only thing to refresh for it is the
`deps/proot` submodule when upstream bumps it.

```sh
git fetch upstream
git rebase upstream/main               # not merge
./scripts/sync_official_binaries.sh    # refresh the vendored pty_bridge/crash_handler/jieba libs
```

**→ See [docs/SYNCING_UPSTREAM.md](docs/SYNCING_UPSTREAM.md)** for the full
procedure, the list of files that conflict, and how to recover from a bad sync.

---

## Privacy

This fork adds no tracking, and upstream ships none. Specifically:

- **No analytics or telemetry SDK.** No Firebase, Crashlytics, Sentry, or
  similar.
- **Crash reports stay on the device.** ACRA is included but with `acra-core`
  only — no network sender is configured. Reports are written to local files and
  surfaced in the app's log screen.
- **No device identifiers are collected.** No IMEI, no advertising ID.
- **The debug server is not in release builds.** A local JSON-RPC server on
  `127.0.0.1:5321` exists for development, gated behind `BuildConfig.DEBUG` and
  compiled out of the release APK published here.

Network traffic goes to the model providers you configure, using your own API
keys, plus the endpoints you explicitly ask the agent to visit. Local backup
files never leave the device unless you share or copy them yourself. If you
choose "include secrets", the JSON contains API keys and environment-variable
values in recoverable form; store that file like a password. MCP OAuth tokens
and client secrets are excluded even from secret-inclusive backups.

The app requests broad permissions (storage, contacts, calendar, microphone,
location, accessibility) because they back agent tools. They are requested at
the point of use — the agent can only use what you grant.

---

## Repository layout

```
src/android/      Android app (Kotlin / Compose)
  app/src/main/jniLibs/arm64-v8a/   Native libs (jieba, pty bridge, crash handler);
                                    libproot.so is a CI build artifact, not vendored
  app/src/main/assets/              Alpine minirootfs
src/shared/       Assets shared with upstream's iOS tree (bashism rules)
deps/             proot source (submodule) + build_proot.sh (NDK r28 build)
docs/             Sync procedure and interface specifications
scripts/          Binary sync and developer tooling
```

---

## Acknowledgements

OpenMinis stands on a great deal of open-source work — full inventory in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md). This fork is derived from
**[OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)** and builds its
sandbox binaries from source: the `deps/proot` submodule (OpenMinis' PRoot
fork, including its native-offload and W^X extensions) is compiled on every CI
run via `deps/build_proot.sh` with NDK r28. No prebuilt sandbox binaries are
committed to this repository.

**The sandbox** — [PRoot](https://github.com/termux/proot) (GPLv2), user-space
chroot for the Android sandbox, via [OpenMinis' fork](https://github.com/OpenMinis/proot);
**[talloc](https://talloc.samba.org)** (LGPLv3+) underpins it;
**[Alpine Linux](https://alpinelinux.org)** — the minirootfs the sandbox boots.

**Text & rendering** — [cppjieba](https://github.com/yanyiwu/cppjieba) (MIT),
[KaTeX](https://katex.org) (MIT).

**Android** — [AndroidX & Jetpack Compose](https://developer.android.com/jetpack),
[OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/),
[kotlinx](https://github.com/Kotlin) serialization & coroutines,
[multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer),
[Reorderable](https://github.com/Calvin-LL/Reorderable), [ACRA](https://github.com/ACRA/acra)
(all Apache-2.0), and [Shizuku](https://github.com/RikkaApps/Shizuku-API) (MIT).

---

## License

OpenMinis is licensed under the **[GNU General Public License v3.0](LICENSE)**.

The app links GPL-licensed components — [PRoot](https://github.com/OpenMinis/proot)
(GPLv2) — so the combined work is distributed under GPLv3. Bundled third-party
licenses are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

---

## Upstream

For the original project, the iOS app, issues and community:

**→ [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)** ·
[openminis.app](https://openminis.app) ·
[Telegram](https://t.me/+2NzhOJuzRyI1YmM1)

For general app bugs, check whether they also occur in the official upstream
build. Upstream issues belong at OpenMinis/OpenMinis; problems with this fork's
build, APK, backup/restore flow, or Android UI changes belong in this repository.
