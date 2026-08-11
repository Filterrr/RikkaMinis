#!/usr/bin/env python3
"""
Enum parse safety check — find bare .valueOf() calls that are NOT wrapped in try/catch.

Persisted enum values (from DB/snapshot/JSON) must NEVER use bare valueOf(),
because adding a new enum value to the Kotlin source will cause any stale
persisted data to crash with IllegalArgumentException on the next read.

Usage: python3 scripts/scan/enum_parse_safety_check.py <repo_root>
Exit code: 0 = clean, 1 = issues found
"""
import re, sys, os

def is_safe_line(line: str) -> bool:
    """Check if .valueOf() on this line is wrapped in try/catch on the same line."""
    idx = line.find('.valueOf(')
    if idx < 0:
        return True
    before = line[:idx]
    after = line[idx + len('.valueOf('):]
    return bool(re.search(r'try\s*\{', before)) and bool(re.search(r'\}\s*catch', after))

def is_safe_across_lines(lines: list, lineno: int, valueof_col: int) -> bool:
    """
    Check if the .valueOf() at (lineno, valueof_col) is inside a try block
    that opened on a previous line.
    """
    # Look backward from lineno-1 to find the nearest try { that hasn't been closed
    # by the time we reach lineno.
    depth = 0
    for i in range(lineno - 2, max(lineno - 15, -1), -1):
        line = lines[i]
        depth += line.count('{') - line.count('}')
        if 'try' in line and '{' in line:
            # Check if 'try {' or 'try{' is before any unmatched '{'
            # Simple check: find the last brace pair
            try_pos = line.find('try')
            if try_pos >= 0 and '{' in line[try_pos:]:
                # The try block is open on this line — see if it's still open
                # when we reach the valueOf line
                # Reset depth from this line only
                new_depth = 0
                for c in line:
                    if c == '{': new_depth += 1
                    elif c == '}': new_depth -= 1
                if new_depth > 0:
                    # try block is still open at end of line
                    # Check if subsequent lines (up to lineno-1) close it
                    for j in range(i + 1, lineno - 1):
                        new_depth += lines[j].count('{') - lines[j].count('}')
                    if new_depth > 0:
                        return True
                break
        if depth <= 0:
            break
    return False

def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    src_dir = os.path.join(root, "src/android/app/src/main")
    problems = []
    for dirpath, _, filenames in os.walk(src_dir):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            with open(path, encoding="utf-8") as f:
                lines = f.readlines()
            for i, line in enumerate(lines, 1):
                if '.valueOf(' not in line:
                    continue
                # Skip if the line itself has a guard pattern
                if re.search(r'\b(runCatching|getOrElse|getOrNull|safeParse|decoded)\b', line):
                    continue
                line_stripped = line.strip()
                # Check same-line try/catch
                if is_safe_line(line_stripped):
                    continue
                # Check multi-line try/catch
                col = line.find('.valueOf(')
                if is_safe_across_lines(lines, i, col):
                    continue
                rel = os.path.relpath(path, root)
                problems.append(f"  {rel}:{i}  {line_stripped}")

    if problems:
        print(f"❌ Bare valueOf calls (not wrapped in try/catch):")
        for p in problems:
            print(p)
        return 1
    else:
        print("✅ All valueOf calls are properly guarded")
        return 0

if __name__ == "__main__":
    sys.exit(main())