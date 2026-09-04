# Syncing with upstream

This fork tracks [`OpenMinis/OpenMinis`](https://github.com/OpenMinis/OpenMinis)
but deliberately diverges in a few well-defined places. This document is the
procedure for pulling in upstream changes without breaking the build.

## What you need to know first

Upstream is a **one-way mirror**, not a normal development repo — its own README
states it does not accept pull requests. It is a periodic public snapshot of a
private repository. Practical consequences:

- History is not guaranteed to be a clean incremental series. A single update
  may arrive as one large squashed commit, and force-pushes are possible.
- Because of that, **do not `git merge` upstream.** A merge would create a
  tangled history that gets harder to reconcile every time. Rebase instead.

## What this fork changes

The divergence is intentionally small, so conflicts stay manageable:

| Area | Change | Conflicts with upstream? |
|---|---|---|
| `src/android/app/build.gradle.kts` | CMake/`externalNativeBuild` disabled, packaging options, version fields | **Likely** — the one file to watch |
| `.github/workflows/build-apk.yml` | Added by this fork | No — upstream has no such file |
| `src/android/app/src/main/jniLibs/arm64-v8a/*.so` | Vendored official binaries (pty_bridge, crash_handler, jieba, c++_shared, datastore, androidx.graphics.path) | No — upstream does not commit these |
| `src/android/app/src/main/assets/ubuntu-base.tar.gz` | Vendored official asset | No — same reason |
| `.gitignore` | Un-ignores the vendored binaries | Minor, easy to resolve |
| `scripts/sync_official_binaries.sh` | Added by this fork | No |
| iOS sources | Deleted | Deletions may reappear; re-delete |

`deps/` (the `deps/proot` submodule, vendored `deps/talloc` and
`deps/build_proot.sh`) matches upstream and should be kept as-is. proot itself
is **built from source** in CI, so it is not part of the sync procedure below.

So in practice **only `build.gradle.kts` needs real attention** during a rebase.

## Why the other binaries must be refreshed every time

This fork does not compile native code through AGP: `externalNativeBuild` is
disabled in `build.gradle.kts`. proot is the exception — it is built from source
in CI via `deps/build_proot.sh` (the `deps/proot` submodule carries upstream's
Android 10+ W^X bypass patches, so the result works on-device). The *other*
native libraries (pty_bridge, crash_handler, jieba, c++_shared, datastore,
androidx.graphics.path) are the official `.so` files, committed as-is.

That means the committed binaries and the Kotlin source **must be kept as a
matched pair**. If upstream changes a JNI method signature — say a parameter is
added to `PtyBridge.forkExec` — the old `.so` no longer matches the new Kotlin
declaration, and the app crashes at runtime.

Never rebase onto new upstream Kotlin without also refreshing the vendored
libraries. proot needs no refresh: it rebuilds from source; bump the
`deps/proot` submodule only when upstream's build inputs change.

## Procedure

```bash
# One-time setup
git remote add upstream https://github.com/OpenMinis/OpenMinis.git

# 1. Fetch and inspect what changed
git fetch upstream
git log --oneline HEAD..upstream/main

# 2. Replay this fork's commits on top of upstream
git rebase upstream/main
#    Resolve conflicts (expect build.gradle.kts). Keep, on our side:
#      - no externalNativeBuild / cmake block
#      - the packaging { jniLibs { ... } } block
#      - our versionCode / versionName
#    Then: git add <file> && git rebase --continue

# 3. Refresh the vendored libraries to match the new source.
#    proot needs no refresh here: it is built from source in CI
#    (deps/build_proot.sh + deps/proot submodule). Only bump deps/proot
#    when upstream changes proot's build inputs.
./scripts/sync_official_binaries.sh

# 4. Review, commit, push
git diff --stat
git add -A
git commit -m "chore: sync with upstream + refresh vendored binaries"
git push --force-with-lease
```

`--force-with-lease` is required because rebasing rewrites commits; it refuses
the push if someone else changed the branch meanwhile, unlike a bare `--force`.

Pushing to `main` triggers `.github/workflows/build-apk.yml`, which compiles
proot from source (NDK r28 + `deps/build_proot.sh`), runs the backup tests,
builds a release APK and publishes it to the `android-latest` release.

## After syncing: verify

The workflow checks that every vendored `.so` is a valid ELF and that the
APK's `libproot.so` matches the freshly compiled one. It cannot catch a JNI
signature mismatch — that only shows up at runtime. So after a sync that pulled
in Kotlin changes, install the APK and confirm:

1. The app starts (rules out a bad `libminis_crash_handler.so` / early JNI load).
2. The terminal opens and runs a command — this is the source-built
   `libproot.so` + `libpty_bridge.so` smoke test, and the thing most likely to break.
3. Chinese text input still segments correctly (exercises `libjieba_jni.so`).

If the terminal reports `Permission denied`, the proot build is broken: check
that `deps/build_proot.sh` ran in CI and that `deps/proot` is at the right
commit — do **not** reach for an extracted binary, that reintroduces the
unpatched build. For the other libs, re-run `sync_official_binaries.sh`, or pin
an older upstream tag: `./scripts/sync_official_binaries.sh 0.22-preview`.

## If a sync goes badly wrong

The rebase is recoverable as long as you have not garbage-collected:

```bash
git rebase --abort          # during a rebase
git reflog                  # find the pre-rebase commit
git reset --hard <sha>      # go back to it
```
