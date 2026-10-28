#!/usr/bin/env python3
"""Resolve JUnit test classes to their fully-qualified names and files.

The local test harness needs BOTH views of the test tree:

  --all            every Subagent test class, fully qualified
  --files          every file that declares a Subagent test class
  <ClassName>     the fully-qualified name of one class
  --file <Class>   the file that DECLARES that class

Why this exists
---------------
A test FILE may declare more than one test CLASS. The harness used to
resolve a class by FILE NAME, so a class whose name differed from its
file was never compiled and never ran — it was green locally and red in
CI. That is the worst possible failure mode for a local harness: it hides
a failure exactly when the failure is real.

Resolving by DECLARATION makes the two views agree: the file that
declares the class is the file that gets compiled, and the class list
comes from the same scan.
"""

import os
import re
import sys

CLASS_RE = re.compile(
    r"^(?:public\s+|internal\s+|open\s+|abstract\s+|sealed\s+)*"
    r"(?:class|object|interface)\s+([A-Za-z_]\w*)",
    re.M,
)
PKG_RE = re.compile(r"^package\s+([\w.]+)", re.M)


def _walk(root):
    for dirpath, _dirs, files in os.walk(root):
        for fn in sorted(files):
            if fn.endswith(".kt"):
                yield os.path.join(dirpath, fn)


def _read(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except Exception:
        return ""


# Tests that belong to the sub-agent subsystem but are NOT named
# "Subagent*Test", so the name filter leaves them out. They are still
# compiled and run: the filter is a naming convention, not a boundary.
#
# [fix/scheduler-test-api-after-merge] `ToolOutputRedactionTest` came in
# with PR #80 and was never compiled locally — the filter decided what
# to COMPILE as well as what to RUN — and it failed to compile in CI
# after the scheduler's API changed underneath it.
EXTRA_TEST_NAMES = {
    "ToolOutputRedactionTest",
    "ToolOutputRedactionWiringTest",
}


def scan(root, want_subagent_only=True):
    """Yield (fqn, path) for every test class found under root."""
    for path in _walk(root):
        text = _read(path)
        pkg = PKG_RE.search(text)
        if not pkg:
            continue
        for m in CLASS_RE.finditer(text):
            name = m.group(1)
            if not name.endswith("Test"):
                continue
            if want_subagent_only and "Subagent" not in name \
                    and name not in EXTRA_TEST_NAMES:
                continue
            yield pkg.group(1) + "." + name, path


def all_test_files(root):
    """Yield every test SOURCE file under root, whatever it is named.

    [fix/scheduler-test-api-after-merge] The source selection used to be
    driven by the same "Subagent" name filter as the class selection, so
    a test file whose class was NOT called a Subagent test was never
    compiled locally. `ToolOutputRedactionTest` — which came in with
    PR #80 — was one of them, and it stayed uncompiled until CI tried to
    build the whole test source set.

    That is how a compile error survives: the local chain compiles a
    SUBSET, the CI chain compiles ALL of it, and the gap is invisible
    until a merge.

    The class filter is a reasonable way to decide what to RUN. It is
    not a reasonable way to decide what to COMPILE. A broken import or
    a renamed method has to be caught by the local chain too, whether
    or not the local chain intends to run that test.
    """
    for path in _walk(root):
        if path.endswith(".kt"):
            yield path


def main(argv):
    root = None
    mode = "class"
    want = ""
    args = list(argv[1:])
    while args:
        a = args.pop(0)
        if a == "--all":
            mode = "all"
        elif a == "--files":
            mode = "files"
        elif a == "--all-files":
            mode = "all-files"
        elif a == "--file":
            mode = "file"
            want = args.pop(0) if args else ""
        elif root is None:
            root = a
        else:
            want = a
    # `--file` takes the class name right after the flag, so the ROOT can
    # land in either position depending on how the caller writes it. The
    # resolver is used from a shell script, so accept both orders rather
    # than making the caller remember one.
    if mode == "file" and root and want:
        # `root` may actually be the class name and `want` the root.
        if not os.path.isdir(root) and os.path.isdir(want):
            root, want = want, root
    if not root:
        sys.stderr.write(
            "usage: resolve_test_classes.py <root> "
            "[--all|--files|--all-files|--file] [Class]\n"
        )
        return 2

    entries = list(scan(root))

    if mode == "all":
        for fqn, _path in entries:
            print(fqn)
        return 0

    if mode == "all-files":
        for path in all_test_files(root):
            print(path)
        return 0

    if mode == "files":
        seen = set()
        for _fqn, path in entries:
            if path not in seen:
                seen.add(path)
                print(path)
        return 0

    for fqn, path in entries:
        if fqn == want or fqn.rsplit(".", 1)[-1] == want:
            if mode == "file":
                print(path)
            else:
                print(fqn)
            return 0
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
