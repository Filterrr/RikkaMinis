#!/usr/bin/env python3
"""TF-E static process-boundary guard.

Mechanical constraint: the app process (`:app`) must NEVER invoke a provider's
network entry points directly. All LLM traffic routes through
ProviderExecutionGateway -> :modelservice. TF-D removed the last in-process
`provider.sendMessage` / `provider.streamMessage` calls and locked it in a JVM
unit test (NoInProcessProviderGuardTest); this scanner is the CI-level,
source-tree gate that runs before every build and every PR.

Rules (scan src/android/app/src/main/java, production only — never tests):

  HARD (exit 1):
   * `provider.sendMessage(`  — only legal in worker ModelExecutionService.kt
   * `provider.streamMessage(` — only legal in worker ModelExecutionService.kt

  SKIPPED:
   * comment lines (//, /*, *, */)
   * the worker file itself (ModelExecutionService.kt) — it OWNS provider calls
   * `viewModel.sendMessage` / `viewModel.streamMessage` — not a provider call

  WARN (informational, exit 0):
   * ProviderFactory.create anywhere outside the worker (currently legitimate
     metadata-only builds: ChatViewModel.currentProvider, ModelUseOffloadHandler).
     The runtime guard (ProviderBoundary in LLMProvider defaults) refuses any
     such provider the moment it attempts a network call.

Usage: sh scripts/scan/provider_boundary_guard.py $ROOT
Exit:  0 clean, 1 offences found.
"""

import os
import re
import sys

WORKER_RE = re.compile(r".*[/\\]offload[/\\]ModelExecutionService\.kt$")

# Hard-flag target patterns: a direct provider network call.
TARGETS = [
    ("provider.sendMessage(", "provider.sendMessage("),
    ("provider.streamMessage(", "provider.streamMessage("),
]

# ProviderFactory.create in the APP process is *informational*: TF-D's remaining
# `create` call sites (ChatViewModel.currentProvider metadata, CLI tool path)
# build providers for metadata/inspection only — they never make a network
# call, and the runtime guard (ProviderBoundary, LLMProvider defaults) refuses
# them the moment one tries. Tracked so the count can be driven to zero.
CREATE_INFO = "ProviderFactory.create("


def is_comment(line: str) -> bool:
    t = line.lstrip()
    return t.startswith("//") or t.startswith("*") or t.startswith("/*")


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: provider_boundary_guard.py <repo-root>")
        return 2
    root = sys.argv[1]
    src = os.path.join(root, "src/android/app/src/main/java")
    if not os.path.isdir(src):
        print(f"⚠️  source tree not found at {src} — skipping (redecorated tree)")
        return 0

    offences = []
    warns = []
    for dirpath, _, fns in os.walk(src):
        for fn in fns:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            display = os.path.relpath(path, root)
            if WORKER_RE.match(path):
                continue  # worker owns provider calls
            with open(path, encoding="utf-8") as f:
                for i, line in enumerate(f, 1):
                    if is_comment(line):
                        continue
                    for pat in TARGETS:
                        if pat[0] in line:
                            offences.append(f"{display}:{i}: {line.strip()}")
                    if CREATE_INFO in line:
                        # Metadata-only construction is tracked (informational).
                        warns.append(f"{display}:{i}: {line.strip()}")

    if offences:
        print("❌ ProviderBoundary: app-process provider network calls found:")
        for o in offences:
            print(f"   - {o}")
        print(
            "   All provider sendMessage/streamMessage MUST live in "
            "ModelExecutionService.kt (:modelservice). Route through "
            "ProviderExecutionGateway instead."
        )
        return 1

    print("✅ ProviderBoundary: no provider network calls outside :modelservice")
    for w in warns:
        print(f"   (info) ProviderFactory.create outside worker (metadata-only): {w}")
    return 0


if __name__ == "__main__":
    sys.exit(main())