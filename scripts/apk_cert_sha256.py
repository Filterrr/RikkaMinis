#!/usr/bin/env python3
"""Print the SHA-256 of an APK's signing certificate.

Parses the v2/v3 APK Signing Block directly, so it needs nothing but the Python
standard library — no apksigner, no keytool. Prints the digest of the first
signer's leaf certificate as lowercase hex.

    python3 scripts/apk_cert_sha256.py app-release.apk

Why this exists: CI must prove the published APK carries the fixed signing key.
Comparing against `keytool -exportcert | sha256sum` of the keystore gives an
exact, format-stable check that does not depend on parsing apksigner's
human-readable output, which varies between build-tools versions.
"""

import hashlib
import struct
import sys

V2_SCHEME_ID = 0x7109871A
V3_SCHEME_ID = 0xF05368C0
MAGIC = b"APK Sig Block 42"


def u32(buf, off):
    return struct.unpack_from("<I", buf, off)[0]


def u64(buf, off):
    return struct.unpack_from("<Q", buf, off)[0]


def find_signing_block(data):
    """Return {scheme_id: bytes} for the pairs in the APK Signing Block."""
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise SystemExit("not a zip/apk: no End Of Central Directory record")
    cd_offset = u32(data, eocd + 16)

    if data[cd_offset - 16:cd_offset] != MAGIC:
        raise SystemExit("no APK Signing Block (unsigned, or v1/JAR-signed only)")

    footer_size = u64(data, cd_offset - 24)
    block_start = cd_offset - footer_size - 8
    block_size = u64(data, block_start)

    pairs = {}
    pos = block_start + 8
    end = block_start + 8 + block_size - 24  # stop before trailing size+magic
    while pos < end:
        pair_len = u64(data, pos)
        pair_id = u32(data, pos + 8)
        pairs[pair_id] = data[pos + 12:pos + 8 + pair_len]
        pos += 8 + pair_len
    return pairs


def first_certificate(block):
    """Extract the first signer's leaf certificate from a v2/v3 block.

    Layout (all sequences are u32 length-prefixed):
        signers -> signer -> signed_data -> [digests][certificates]...
    """
    signer_len = u32(block, 4)                 # skip the outer signers length
    signer = block[8:8 + signer_len]

    signed_data_len = u32(signer, 0)
    signed_data = signer[4:4 + signed_data_len]

    digests_len = u32(signed_data, 0)
    certs_off = 4 + digests_len                # skip the digests sequence
    cert_len = u32(signed_data, certs_off + 4)  # +4 skips the certs seq length
    cert = signed_data[certs_off + 8:certs_off + 8 + cert_len]

    if not cert.startswith(b"\x30\x82"):
        raise SystemExit("parsed bytes are not a DER certificate — layout changed?")
    return cert


def main():
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} <apk>")

    data = open(sys.argv[1], "rb").read()
    pairs = find_signing_block(data)

    for scheme_id in (V2_SCHEME_ID, V3_SCHEME_ID):
        if scheme_id in pairs:
            cert = first_certificate(pairs[scheme_id])
            print(hashlib.sha256(cert).hexdigest())
            return

    raise SystemExit("APK has a signing block but no v2/v3 signature")


if __name__ == "__main__":
    main()
