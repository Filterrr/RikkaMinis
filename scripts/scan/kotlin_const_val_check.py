#!/usr/bin/env python3
"""Kotlin structural sanity scanner — catches `const val` placement errors.

Rationale: a `private const val` inside a plain class body is a compile
error that slipped through a PR whose CI check ran only scan jobs (no
compile), then failed the release build (compileReleaseKotlin). This
scanner reproduces kotlinc's placement rule cheaply, before any toolchain
is set up:

  `const val` is legal ONLY at:
    * top level                      (brace stack empty)
    * inside an object / companion object declaration (any scope in the
      open-brace chain was opened by an `object` declaration)

Implementation notes (learned the hard way — v1 of this scanner had both
false positives and false negatives):
  * Comments and string/char literals are blanked out with a CHARACTER-
    PRESERVING state machine: newlines and line numbering survive, braces
    inside strings never corrupt the scope stack.
  * A declaration is `object`-scoped when a line STARTS with (modifiers)
    `companion object` (any form) or `object Name` (named). Anonymous
    `object : Foo {}` expressions do NOT match — kotlinc rejects const val
    inside them too ("named objects" only), so flagging matches the
    compiler. Class headers like `class ObjectFactory` never match.
  * Anonymous `object : Foo {}` expressions are treated as object scopes:
    permissive on purpose (kotlinc still rejects const val there; the
    scanner must never block a legal build).

Usage: python3 scripts/scan/kotlin_const_val_check.py <repo-root>
Exit:  0 = clean, 1 = violations found.
"""
import os
import re
import sys

CONST_VAL = re.compile(
    r'^\s*(?:private\s+|internal\s+|public\s+)?const\s+val\s+[A-Za-z_]'
)
OBJECT_DECL = re.compile(
    r'^\s*(?:private\s+|internal\s+|public\s+|sealed\s+)*'
    r'(?:companion\s+object\b|object\s+[A-Za-z_])'
)


def blank_literals(text: str) -> str:
    """Blank out comments and string/char literals, preserving length and
    newlines so line numbers and brace structure stay exact."""
    out = []
    i, n = 0, len(text)
    state = None  # None | 'line' | 'block' | 'str' | 'rawstr' | 'char'
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ''
        if state is None:
            if c == '/' and nxt == '/':
                state = 'line'
                out.append('  ')
                i += 2
                continue
            if c == '/' and nxt == '*':
                state = 'block'
                out.append('  ')
                i += 2
                continue
            if c == '"':
                if text[i:i + 3] == '"""':
                    state = 'rawstr'
                    out.append('"""')
                    i += 3
                    continue
                state = 'str'
                out.append('"')
                i += 1
                continue
            if c == "'":
                state = 'char'
                out.append("'")
                i += 1
                continue
            out.append(c)
            i += 1
        elif state == 'line':
            if c == '\n':
                state = None
                out.append('\n')
            else:
                out.append(' ')
            i += 1
        elif state == 'block':
            if c == '*' and nxt == '/':
                state = None
                out.append('  ')
                i += 2
                continue
            out.append('\n' if c == '\n' else ' ')
            i += 1
        elif state in ('str', 'rawstr'):
            closer = '"""' if state == 'rawstr' else '"'
            if state == 'str' and c == '\\':
                out.append('  ')
                i += 2
                continue
            if state == 'rawstr' and text[i:i + 3] == '"""':
                state = None
                out.append('"""')
                i += 3
                continue
            if state == 'str' and c == '"':
                state = None
                out.append('"')
                i += 1
                continue
            out.append('\n' if c == '\n' else ' ')
            i += 1
        elif state == 'char':
            if c == '\\':
                out.append('  ')
                i += 2
                continue
            if c == "'":
                state = None
                out.append("'")
                i += 1
                continue
            out.append(' ')
            i += 1
    return ''.join(out)


def scan_file(path: str):
    """Return [(line_no, snippet)] for illegal const val declarations."""
    with open(path, encoding='utf-8') as f:
        raw = f.read()
    code = blank_literals(raw)
    raw_lines = raw.splitlines()

    violations = []
    stack = []              # one bool per open brace: opened-by-object-decl?
    pending_object = False  # saw an object declaration header awaiting '{'

    for idx, line in enumerate(code.splitlines(), 1):
        if OBJECT_DECL.match(line):
            pending_object = True
        if CONST_VAL.match(line):
            # Legal: top level (stack empty) or any object scope in chain.
            if stack and not any(stack):
                violations.append((idx, raw_lines[idx - 1].strip()[:90]))
        for ch in line:
            if ch == '{':
                stack.append(pending_object)
                pending_object = False
            elif ch == '}':
                if stack:
                    stack.pop()
            elif ch == '\n':
                # A declaration header followed by no brace on its line
                # stays pending across exactly that line; a blank/comment
                # line in between is fine, a non-declaration code line is
                # not — reset there to avoid stale headers.
                if pending_object and line.strip() and not OBJECT_DECL.match(line):
                    pending_object = False
    return violations


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    src_dir = os.path.join(root, 'src/android/app/src')
    violations = []
    files_scanned = 0
    for dirpath, _, filenames in os.walk(src_dir):
        for fn in sorted(filenames):
            if not fn.endswith('.kt'):
                continue
            path = os.path.join(dirpath, fn)
            files_scanned += 1
            for line_no, snippet in scan_file(path):
                rel = os.path.relpath(path, root)
                violations.append((rel, line_no, snippet))

    if violations:
        print("❌ Illegal `const val` placement (only top-level or object scopes):")
        for rel, line_no, snippet in violations:
            print(f"   {rel}:{line_no}: {snippet}")
        return 1
    print(f"✅ const val placement OK ({files_scanned} Kotlin files)")
    return 0


if __name__ == '__main__':
    sys.exit(main())
