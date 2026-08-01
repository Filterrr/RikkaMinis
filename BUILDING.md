# Building

This fork is deliberately easy to build: **no NDK, no submodules, no rootfs
preparation.** The native sandbox binaries are committed to the repository, so a
build is a plain Gradle run.

```sh
git clone https://github.com/logicflow-GYW/OpenMinis.git
cd OpenMinis/src/android
./gradlew assembleRelease
```

The APK lands in `src/android/app/build/outputs/apk/release/app-release.apk`.

If you only want to *install* the app, you do not need any of this — grab the
[latest APK](https://github.com/logicflow-GYW/OpenMinis/releases/tag/android-latest).

---

## Requirements

| Tool | Version |
|---|---|
| JDK | **17** (Temurin recommended) |
| Android SDK | **compileSdk 36**, targetSdk 35, minSdk 26 |
| Gradle | 8.11.1 — provided by the wrapper, do not install separately |

Android Studio bundles a suitable JDK and SDK; opening `src/android` as a
project works out of the box. From the command line, `ANDROID_HOME` (or
`ANDROID_SDK_ROOT`) must point at your SDK.

**You do not need the NDK or CMake.** Native compilation is disabled — see
below.

---

## Debug vs release

```sh
./gradlew assembleDebug      # ~39 MB, no code shrinking
./gradlew assembleRelease    # ~14 MB, R8-minified — what CI publishes
```

Prefer `assembleRelease` unless you are actively debugging. A debug APK is
roughly three times larger because R8 does not run, and it also embeds a local
JSON-RPC debug server on `127.0.0.1:5321` that release builds compile out.

Both variants are signed with the `debug` signing config — i.e. your
`~/.android/debug.keystore`, generated automatically on first use. This means a
locally built APK will **not** install over one downloaded from Releases: the
signing keys differ. Uninstall first, or install the same keystore CI uses.

---

## Why there is no native build step

The app runs a Linux sandbox via PRoot. Its engine, `libproot.so`, needs
upstream's Android 10+ W^X bypass patches; a version rebuilt from source
compiles cleanly and then fails at runtime the moment the terminal opens:

```
execve("/bin/sh"): Permission denied
```

Rather than reproduce that patched toolchain in CI, this fork commits the
official prebuilt `arm64-v8a` binaries, extracted verbatim from an upstream
release APK and verified by sha256:

```
src/android/app/src/main/jniLibs/arm64-v8a/*.so     9 libraries
src/android/app/src/main/assets/alpine-minirootfs.tar
src/android/app/src/main/assets/proot-aarch64
```

Two settings in `app/build.gradle.kts` protect them:

- `externalNativeBuild` is **removed**. Left enabled, AGP would compile
  `src/main/cpp/` and overwrite `libpty_bridge.so`, `libjieba_jni.so` and
  `libminis_crash_handler.so` with locally built copies.
- `packaging { jniLibs { keepDebugSymbols += "**/*.so" } }` stops AGP running
  the NDK's `strip` over already-stripped official binaries.
- `useLegacyPackaging = true` keeps the libraries extracted to
  `nativeLibraryDir` at install time. This is load-bearing: `RootfsManager`
  executes `libproot.so` as a **binary** from that directory, and that is
  precisely the mechanism that sidesteps W^X.

**Consequence:** changes under `src/android/app/src/main/cpp/` are not
compiled. To work on native code, restore the `externalNativeBuild` block
(the removed configuration is documented inline in `build.gradle.kts`) and
install NDK r28+.

---

## Refreshing the vendored binaries

```sh
./scripts/sync_official_binaries.sh              # newest upstream Android release
./scripts/sync_official_binaries.sh 0.22-preview # a specific tag
```

This downloads the official APK, replaces the committed binaries, prints
sha256s, and aligns `versionName` / `versionCode`. Run it after every rebase
onto upstream — see [docs/SYNCING_UPSTREAM.md](docs/SYNCING_UPSTREAM.md) for why
that is mandatory rather than optional.

---

## Build-time customization

`app/provider-customization.properties.example` is a template for values that
are not shipped in the public tree. Copy it to
`app/provider-customization.properties` and fill in what you need:

- `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT` — only required to sign in with Claude
  **OAuth** credentials. API keys do not need it.

A build with empty values compiles fine and fails at runtime the first time a
missing value is actually required.

---

## Continuous integration

`.github/workflows/build-apk.yml` runs on every push to `main` that touches
`src/android/`, `src/shared/` or the workflow itself, and can be triggered
manually from the Actions tab. It:

1. Restores the signing keystore from the `DEBUG_KEYSTORE_B64` secret
2. Verifies each vendored `.so` is a valid ELF file
3. Runs `assembleRelease`
4. Asserts the `libproot.so` inside the APK matches the committed binary
5. Publishes the APK to the `android-latest` release

### Signing in CI

`DEBUG_KEYSTORE_B64` holds a base64-encoded `debug.keystore`. It is a **repo
secret, not a file in the tree** — keystores are credentials and must not be
committed.

The workflow decodes it to `$RUNNER_TEMP/ci-signing.keystore` and exports
`MINIS_KEYSTORE_PATH`; `build.gradle.kts` reads that variable and points the
`debug` signing config at it.

That indirection is necessary, not decorative. AGP's default is
`$HOME/.android/debug.keystore`, but `actions/checkout` temporarily overrides
`HOME` on the runner — so a keystore written to `~/.android/` is restored to a
path Gradle never reads, and AGP quietly generates a throwaway key instead. The
build succeeds, the APK looks fine, and it then refuses to install over an
existing one with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. This bit us once; the
`Verify APK signature` step exists so it cannot happen silently again. It
compares the certificate embedded in the built APK against the keystore, using
`scripts/apk_cert_sha256.py`.

Losing it means losing upgrade continuity: a new key produces APKs that will not
install over existing ones, and every user has to uninstall and reinstall. Back
it up somewhere durable.

To rotate or recreate it:

```sh
keytool -genkeypair -v -keystore debug.keystore \
  -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 \
  -validity 10950 -dname "CN=Android Debug,O=Android,C=US"

base64 -w0 debug.keystore   # paste into the repo secret
```

The storepass, keypass and alias above are the Android defaults and must stay
as-is — Gradle's `debug` signing config looks for exactly those.

---

## Troubleshooting

**`SDK location not found`** — set `ANDROID_HOME`, or create
`src/android/local.properties` containing `sdk.dir=/path/to/Android/sdk`.

**`Failed to install ... INSTALL_FAILED_UPDATE_INCOMPATIBLE`** — the installed
app was signed with a different key (e.g. the official build, or your local
debug key vs. CI's). Uninstall it first.

**`INSTALL_FAILED_VERSION_DOWNGRADE`** — the APK's `versionCode` is lower than
what is installed. Uninstall, or bump `versionCode` in `build.gradle.kts`.

**Terminal opens then immediately reports `Permission denied`** — the vendored
binaries do not match the Kotlin source, usually after a rebase onto upstream
without re-running `sync_official_binaries.sh`. Re-run it, or pin the release
the source came from.

**App builds but crashes on launch after a sync** — likely a JNI signature
mismatch between new Kotlin and old `.so` files. Same fix as above. `adb logcat`
will name the missing method.

**Gradle runs out of memory** — raise the heap in `src/android/gradle.properties`:
`org.gradle.jvmargs=-Xmx4096m`.
