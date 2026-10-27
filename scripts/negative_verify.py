#!/usr/bin/env python3
"""Negative verification for the sub-agent guards.

A negative verification removes (or reverses) the thing a guard is
supposed to protect, then asserts the guard goes RED. If the guard stays
green, the guard was not guarding anything.

THE POINT OF THIS SCRIPT
------------------------

Writing these by hand kept going wrong in one specific way: the
replacement anchor did not match the file, so the "negative" edit was a
no-op, and the guard stayed green — which reads exactly like "the guard
is insensitive" and sends you off to debug a test that was fine.

This script therefore REFUSES to run an edit that changed nothing, and
it restores the file even when the assertion fails.

    python3 scripts/negative_verify.py \
        --file src/.../AgentCapabilities.kt \
        --replace 'OLD TEXT' 'NEW TEXT' \
        --expect-red 'SubagentHardForbiddenTest'

    python3 scripts/negative_verify.py \
        --file src/.../SubagentSkill.kt \
        --insert-after '"memory_rollup",' '        "todo_write",' \
        --expect-red 'SubagentHardForbiddenTest'

Exit codes:
    0  the guard went red as expected (and the file was restored)
    1  the guard stayed green — the guard is not sensitive
    2  the edit did not apply — the verification itself was broken
    3  the test run failed for some other reason
"""

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
TEST_SCRIPT = REPO / "scripts" / "run_subagent_tests.sh"


def apply_edit(path: Path, replace, insert_after) -> str:
    original = path.read_text(encoding="utf-8")
    text = original

    if replace:
        old, new = replace
        if old not in text:
            return ""
        text = text.replace(old, new, 1)

    if insert_after:
        anchor, addition = insert_after
        if anchor not in text:
            return ""
        lines = text.split("\n")
        for i, line in enumerate(lines):
            if anchor in line:
                lines.insert(i + 1, addition)
                break
        else:
            return ""
        text = "\n".join(lines)

    if text == original:
        return ""
    path.write_text(text, encoding="utf-8")
    return original


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", required=True, help="path to the file to perturb")
    ap.add_argument("--replace", nargs=2, metavar=("OLD", "NEW"))
    ap.add_argument("--insert-after", nargs=2, metavar=("ANCHOR", "LINE"))
    ap.add_argument("--expect-red", required=True, help="test class that must go red")
    args = ap.parse_args()

    path = (REPO / args.file) if not Path(args.file).is_absolute() else Path(args.file)
    if not path.is_file():
        print(f"ERROR: no such file: {path}", file=sys.stderr)
        return 2

    backup = Path(tempfile.mkdtemp()) / path.name
    shutil.copy2(path, backup)

    try:
        original = apply_edit(path, args.replace, args.insert_after)
        if original == "":
            print(
                "ERROR: the edit did not apply — the anchor does not match the file.\n"
                "       This is a BROKEN VERIFICATION, not an insensitive guard.\n"
                "       (The usual cause: the anchor is a constant reference in the\n"
                "        source, not the literal string you expected.)",
                file=sys.stderr,
            )
            return 2

        proc = subprocess.run(
            ["sh", str(TEST_SCRIPT), args.expect_red],
            cwd=REPO, capture_output=True, text=True,
        )
        out = proc.stdout + proc.stderr

        # The guard went red if the run reported failures.
        went_red = ("FAILURES!!!" in out) or ("Failures: 0" not in out and
                                              "OK (" not in out and proc.returncode != 0)
        if went_red:
            print(f"PASS: the guard went red after the edit ({args.expect_red}).")
            for line in out.splitlines():
                if "AssertionError" in line or line.startswith("Tests run:"):
                    print("   " + line.strip())
            return 0

        print(
            "FAIL: the guard stayed GREEN after the edit — it is not sensitive.\n"
            f"      test: {args.expect_red}\n"
            f"      file: {path}",
            file=sys.stderr,
        )
        for line in out.splitlines()[-12:]:
            print("   " + line, file=sys.stderr)
        return 1
    finally:
        shutil.copy2(backup, path)
        print(f"restored {path}")


if __name__ == "__main__":
    sys.exit(main())
