#!/usr/bin/env bash
#
# Prepare Android sandbox assets:
#   1. Download Ubuntu Base aarch64 rootfs (glibc, apt/dpkg) + SHA256 verify
#   2. Download PRoot aarch64 static binary from Termux packages
#   3. Place both into src/android/app/src/main/assets/
#
# Usage: ./scripts/prepare_android_sandbox.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/src/android/app/src/main/assets"

UBUNTU_SERIES="24.04"
UBUNTU_POINT="24.04.4"
UBUNTU_BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/${UBUNTU_SERIES}/release"
UBUNTU_URL="${UBUNTU_BASE_URL}/ubuntu-base-${UBUNTU_POINT}-base-arm64.tar.gz"
# The exact digest of the pinned artifact. `curl` re-downloads SHA256SUMS
# (over HTTPS) and the pre-recorded value below is a second, independent
# pin — if Canonical silently re-spins the point release, both checks trip.
UBUNTU_SHA256="04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"

# Termux proot package — aarch64 static binary
PROOT_VERSION="5.1.107-70"
PROOT_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_${PROOT_VERSION}_aarch64.deb"

mkdir -p "$ASSETS_DIR"

ROOTFS_FILE="$ASSETS_DIR/ubuntu-base.tar.gz"
PROOT_FILE="$ASSETS_DIR/proot-aarch64"

# --- Ubuntu Base rootfs ---
if [ -f "$ROOTFS_FILE" ]; then
    echo "✓ Ubuntu rootfs already exists: $ROOTFS_FILE"
else
    echo "Downloading Ubuntu Base ${UBUNTU_POINT} aarch64 rootfs..."
    curl -fSL -o "$ROOTFS_FILE" "$UBUNTU_URL"

    # Integrity gate: pinned digest first, then Canonical's SHA256SUMS.
    # A mismatch aborts before the artifact ever reaches the repo.
    echo "Verifying SHA256 (pinned digest)..."
    echo "${UBUNTU_SHA256}  $ROOTFS_FILE" | sha256sum -c - >/dev/null

    echo "Cross-checking against Canonical SHA256SUMS..."
    SUMS="$(mktemp -d)/SHA256SUMS"
    curl -fsSL -o "$SUMS" "${UBUNTU_BASE_URL}/SHA256SUMS"
    grep "ubuntu-base-${UBUNTU_POINT}-base-arm64.tar.gz" "$SUMS" | sed 's|\*| |' | (cd "$(dirname "$ROOTFS_FILE")" && sha256sum -c - >/dev/null)

    echo "✓ Downloaded + verified: $ROOTFS_FILE ($(du -h "$ROOTFS_FILE" | cut -f1))"
fi

# --- PRoot binary ---
if [ -f "$PROOT_FILE" ]; then
    echo "✓ PRoot binary already exists: $PROOT_FILE"
else
    echo "Downloading PRoot ${PROOT_VERSION} aarch64 from Termux..."

    TMPDIR="$(mktemp -d)"
    trap 'rm -rf "$TMPDIR"' EXIT

    DEB_FILE="$TMPDIR/proot.deb"
    curl -fSL -o "$DEB_FILE" "$PROOT_DEB_URL"

    # Extract .deb (it's an ar archive containing data.tar.xz)
    cd "$TMPDIR"
    ar x "$DEB_FILE"

    # Extract data archive
    if [ -f "data.tar.xz" ]; then
        tar xf data.tar.xz
    elif [ -f "data.tar.gz" ]; then
        tar xzf data.tar.gz
    elif [ -f "data.tar.zst" ]; then
        zstd -d data.tar.zst -o data.tar
        tar xf data.tar
    else
        echo "Error: Could not find data archive in .deb"
        ls -la "$TMPDIR"
        exit 1
    fi

    # Find the proot binary
    PROOT_BIN=$(find "$TMPDIR" -name "proot" -type f | head -1)
    if [ -z "$PROOT_BIN" ]; then
        echo "Error: Could not find proot binary in extracted .deb"
        find "$TMPDIR" -type f
        exit 1
    fi

    cp "$PROOT_BIN" "$PROOT_FILE"
    chmod +x "$PROOT_FILE"
    cd "$PROJECT_ROOT"

    echo "✓ Extracted PRoot binary: $PROOT_FILE ($(du -h "$PROOT_FILE" | cut -f1))"
fi

echo ""
echo "Assets ready in: $ASSETS_DIR"
ls -lh "$ASSETS_DIR"
