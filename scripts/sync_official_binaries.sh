#!/usr/bin/env bash
#
# Refresh the vendored prebuilt sandbox binaries from an official upstream
# release APK.
#
# WHY THIS EXISTS
# ---------------
# This fork does not build native code (see src/android/app/build.gradle.kts —
# externalNativeBuild is disabled). It ships the official arm64-v8a binaries
# committed under src/android/app/src/main/{jniLibs,assets}/ instead, because a
# self-built libproot.so lacks upstream's Android 10+ W^X bypass and dies at
# runtime with execve("/bin/sh"): Permission denied.
#
# The consequence: after every `git rebase upstream/main` you MUST re-run this
# script. Upstream's Kotlin may have changed a JNI method signature, and the
# old .so files would then crash at runtime. Kotlin source and these binaries
# have to move together.
#
# USAGE
#   ./scripts/sync_official_binaries.sh              # latest upstream release
#   ./scripts/sync_official_binaries.sh 0.23-preview # a specific tag
#
# Requires: curl, unzip, python3. GITHUB_TOKEN is optional (raises API rate
# limits); the release assets themselves are public.

set -euo pipefail

UPSTREAM_REPO="OpenMinis/OpenMinis"
REQUESTED_TAG="${1:-}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN_DIR="$REPO_ROOT/src/android/app/src/main"
JNI_DIR="$MAIN_DIR/jniLibs/arm64-v8a"
ASSETS_DIR="$MAIN_DIR/assets"
GRADLE_FILE="$REPO_ROOT/src/android/app/build.gradle.kts"

# The exact set this fork vendors. Keep in sync with build.gradle.kts.
JNI_LIBS="
libandroidx.graphics.path.so
libc++_shared.so
libdatastore_shared_counter.so
libjieba_jni.so
libminis_crash_handler.so
libproot-loader.so
libproot-loader32.so
libproot.so
libpty_bridge.so
"
ASSET_FILES="
alpine-minirootfs.tar
proot-aarch64
"

auth_header() {
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    printf 'Authorization: Bearer %s' "$GITHUB_TOKEN"
  else
    printf 'X-No-Auth: 1'
  fi
}

# Explicit template: BusyBox mktemp (Alpine) needs one, unlike GNU coreutils.
# Also don't trust TMPDIR — some sandboxes export a path that doesn't exist.
TMP_BASE="${TMPDIR:-/tmp}"
[ -d "$TMP_BASE" ] && [ -w "$TMP_BASE" ] || TMP_BASE=/tmp
WORK_DIR="$(mktemp -d "$TMP_BASE/minis-sync.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

# --- 1. Resolve the release and its APK asset -------------------------------
# Not /releases/latest: upstream publishes iOS and Android releases to the same
# repo, so "latest" is often an iOS tag with no APK attached. Scan the release
# list instead and take the newest one that actually ships an arm64 .apk.
echo "==> Querying upstream release (${REQUESTED_TAG:-latest Android})"
if [ -n "$REQUESTED_TAG" ]; then
  curl -fsSL -H "$(auth_header)" \
    "https://api.github.com/repos/$UPSTREAM_REPO/releases/tags/$REQUESTED_TAG" \
    -o "$WORK_DIR/release.json"
else
  curl -fsSL -H "$(auth_header)" \
    "https://api.github.com/repos/$UPSTREAM_REPO/releases?per_page=50" \
    -o "$WORK_DIR/releases.json"
  python3 - "$WORK_DIR/releases.json" "$WORK_DIR/release.json" <<'PY'
import json, sys
releases = json.load(open(sys.argv[1]))
for rel in releases:  # API returns newest first
    if any(a["name"].endswith(".apk") and "arm64" in a["name"]
           for a in rel.get("assets", [])):
        json.dump(rel, open(sys.argv[2], "w"))
        break
else:
    sys.exit("No release with an arm64 .apk asset found.")
PY
fi

eval "$(python3 - "$WORK_DIR/release.json" <<'PY'
import json, sys, shlex
rel = json.load(open(sys.argv[1]))
apk = next(
    (a for a in rel.get("assets", [])
     if a["name"].endswith(".apk") and "arm64" in a["name"]),
    None,
)
if apk is None:
    sys.exit("No arm64 .apk asset found in that release.")
print("TAG=" + shlex.quote(rel["tag_name"]))
print("APK_NAME=" + shlex.quote(apk["name"]))
print("APK_URL=" + shlex.quote(apk["browser_download_url"]))
PY
)"

echo "    release : $TAG"
echo "    asset   : $APK_NAME"

# --- 2. Download and unpack -------------------------------------------------
echo "==> Downloading APK"
curl -fsSL -o "$WORK_DIR/official.apk" "$APK_URL"

echo "==> Extracting binaries"
mkdir -p "$WORK_DIR/x"
unzip -o -q "$WORK_DIR/official.apk" 'lib/arm64-v8a/*' 'assets/*' -d "$WORK_DIR/x"

# Verify everything we expect is present BEFORE touching the working tree, so a
# malformed release can't leave the repo half-updated.
missing=0
for f in $JNI_LIBS; do
  [ -f "$WORK_DIR/x/lib/arm64-v8a/$f" ] || { echo "    MISSING lib: $f"; missing=1; }
done
for f in $ASSET_FILES; do
  [ -f "$WORK_DIR/x/assets/$f" ] || { echo "    MISSING asset: $f"; missing=1; }
done
if [ "$missing" -ne 0 ]; then
  echo "ERROR: upstream release layout changed — refusing to update." >&2
  echo "Inspect $APK_NAME by hand and adjust this script." >&2
  exit 1
fi

# --- 3. Copy into the working tree ------------------------------------------
echo "==> Updating vendored files"
mkdir -p "$JNI_DIR" "$ASSETS_DIR"
for f in $JNI_LIBS; do
  cp -f "$WORK_DIR/x/lib/arm64-v8a/$f" "$JNI_DIR/$f"
  printf '    %s  %s\n' "$(sha256sum "$JNI_DIR/$f" | cut -c1-16)" "jniLibs/$f"
done
for f in $ASSET_FILES; do
  cp -f "$WORK_DIR/x/assets/$f" "$ASSETS_DIR/$f"
  printf '    %s  %s\n' "$(sha256sum "$ASSETS_DIR/$f" | cut -c1-16)" "assets/$f"
done

# libproot.so and assets/proot-aarch64 are the same binary shipped twice; a
# mismatch means the release was assembled oddly and the sandbox may misbehave.
if [ "$(sha256sum "$JNI_DIR/libproot.so" | cut -d' ' -f1)" \
   != "$(sha256sum "$ASSETS_DIR/proot-aarch64" | cut -d' ' -f1)" ]; then
  echo "WARNING: libproot.so and assets/proot-aarch64 differ in this release." >&2
fi

# --- 4. Align versionName / versionCode with the binaries -------------------
# Keeps "which upstream release is this built from" answerable from the app's
# About screen. versionCode is derived from the numeric part of the tag.
# Tags look like "0.22-preview": the MINOR component is what upstream tracks as
# versionCode (0.22 -> 22). Taking the first number instead would yield 0, and a
# versionCode that low blocks upgrade-installs over an existing build.
VERSION_NAME="${TAG}"
VERSION_CODE="$(printf '%s' "$TAG" | sed -n 's/^[0-9]*\.\([0-9]*\).*/\1/p')"
# Fall back to the largest number in the tag if it isn't in MAJOR.MINOR form.
[ -n "$VERSION_CODE" ] || VERSION_CODE="$(
  printf '%s' "$TAG" | grep -oE '[0-9]+' | sort -rn | head -1 || true
)"
VERSION_CODE="$(printf '%s' "$VERSION_CODE" | sed 's/^0*//')"
[ -n "$VERSION_CODE" ] || VERSION_CODE=""
# Never let versionCode go backwards: Android refuses to install an APK whose
# versionCode is lower than the installed one, which would break upgrades.
CURRENT_CODE="$(grep -oE 'versionCode = [0-9]+' "$GRADLE_FILE" | head -1 | grep -oE '[0-9]+' || echo 0)"
if [ -n "$VERSION_CODE" ] && [ "$VERSION_CODE" -lt "$CURRENT_CODE" ]; then
  echo "==> Computed versionCode $VERSION_CODE < current $CURRENT_CODE; keeping $CURRENT_CODE."
  VERSION_CODE="$CURRENT_CODE"
fi

if [ -n "$VERSION_CODE" ]; then
  echo "==> Aligning version to $VERSION_NAME (versionCode $VERSION_CODE)"
  python3 - "$GRADLE_FILE" "$VERSION_NAME" "$VERSION_CODE" <<'PY'
import re, sys
path, name, code = sys.argv[1], sys.argv[2], sys.argv[3]
src = open(path).read()
src, n1 = re.subn(r'versionCode = \d+', f'versionCode = {code}', src, count=1)
src, n2 = re.subn(r'versionName = "[^"]*"', f'versionName = "{name}"', src, count=1)
open(path, "w").write(src)
if not (n1 and n2):
    sys.exit("WARNING: could not rewrite version fields — check build.gradle.kts")
PY
else
  echo "==> Tag '$TAG' has no numeric part; leaving version fields alone."
fi

# --- 5. Report --------------------------------------------------------------
cat <<EOF

Done. Vendored binaries now come from upstream $TAG.

Next steps:
  git diff --stat
  git add -A && git commit -m "chore: sync prebuilt binaries from upstream $TAG"
  git push

Pushing to main triggers the build workflow, which publishes the APK to the
'android-latest' release.

Reminder: if upstream also changed Kotlin code, rebase onto it FIRST, then run
this script, so source and binaries ship as a matched pair.
EOF
