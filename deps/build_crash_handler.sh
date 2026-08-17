#!/bin/bash
set -e

# ============================================================================
# Minis Crash Handler Native Build (OpenMinis fork)
# ============================================================================
# Cross-compiles crash_handler.cpp for Android aarch64 using the Android NDK.
# The main build (build.gradle.kts) has externalNativeBuild (CMake) DISABLED on
# purpose (proot gets cross-compiled by build_proot.sh; letting CMake drive the
# whole thing would rebuild libproot.so incorrectly and execve-fail under W^X).
# So native helpers that ARE cmake-excluded (crash_handler, jieba_jni, pty_bridge)
# ship as VENDORED prebuilt .so files under:
#   src/android/app/src/main/jniLibs/arm64-v8a/
#
# To keep a vendored .so in sync with its source, rebuild it with the
# exact same NDK toolchain used by build_proot.sh and re-commit the binary.
#
# Output:
#   src/android/app/src/main/jniLibs/arm64-v8a/libminis_crash_handler.so
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CPP="$PROJECT_ROOT/src/android/app/src/main/cpp/crash_handler.cpp"
JNILIBS_DIR="$PROJECT_ROOT/src/android/app/src/main/jniLibs/arm64-v8a"
OUT="$JNILIBS_DIR/libminis_crash_handler.so"

[ -f "$CPP" ] || { echo "ERROR: source not found: $CPP" >&2; exit 1; }

# Android target — keep in sync with minSdk in app/build.gradle.kts and
# ANDROID_API used by build_proot.sh.
ANDROID_API=26
NDK_TRIPLE="aarch64-linux-android"

# ---- Locate NDK (same resolution as build_proot.sh) -------------------------
if [ -n "$ANDROID_NDK_HOME" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    NDK_HOME="$ANDROID_NDK_HOME"
elif [ -n "$ANDROID_NDK_ROOT" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
    NDK_HOME="$ANDROID_NDK_ROOT"
else
    host_tag="linux-x86_64"
    candidates=(
        "$ANDROID_HOME/ndk/28.0.12433566"
        "$HOME/Android/Sdk/ndk/28.0.12433566"
        "$HOME/Library/Android/sdk/ndk/28.0.12433566"
    )
    NDK_HOME=""
    for c in "${candidates[@]}"; do
        if [ -d "$c" ]; then NDK_HOME="$c"; break; fi
    done
    [ -n "$NDK_HOME" ] || { echo "ERROR: NDK not found (set ANDROID_NDK_HOME)" >&2; exit 1; }
fi

host_tag="$(uname -s)-$(uname -m)"
case "$host_tag" in
    Linux-*) host_bin="linux-x86_64" ;;
    Darwin-*) host_bin="darwin-x86_64" ;;
    *) host_bin="unknown" ;;
esac
TOOLCHAIN_BIN="$NDK_HOME/toolchains/llvm/prebuilt/$host_bin/bin"
CC="$TOOLCHAIN_BIN/${NDK_TRIPLE}${ANDROID_API}-clang"
[ -x "$CC" ] || { echo "ERROR: clang missing: $CC" >&2; exit 1; }
echo "Using clang: $CC"

mkdir -p "$JNILIBS_DIR"

# -fPIC -shared == shared library. -O2 default safe opts; -DANDROID not needed.
# Link -llog (android/log.h -> __android_log_print). -Wl,soname optional but nice.
# TODO(optional): add -fno-exceptions / -fomit-frame-pointer to match prior .so size if desired.
$CC -fPIC -shared -O2 \
    -Wl,-soname,libminis_crash_handler.so \
    "$CPP" -llog -o "$OUT"

echo "Wrote: $OUT"
ls -la "$OUT"
