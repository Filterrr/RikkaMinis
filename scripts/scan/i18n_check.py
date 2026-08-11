#!/usr/bin/env python3
"""
i18n consistency check — detect orphan resource keys and missing translations.

Checks:
1. Orphan keys: referenced in code (R.string.xxx) but not defined in values/strings.xml
   → HARD FAIL (exit 1). These are real bugs: they fail at runtime when the
     resource lookup happens.
2. Missing translations: keys defined in values/ but missing from a locale file
   → WARNING only (known legacy from upstream — de/ja/ko/ru/zh-rTW were never
     fully translated; hard-failing on them would block every build).

Usage: python3 scripts/scan/i18n_check.py <repo_root>
Exit code: 0 = clean, 1 = orphan keys found
"""
import re, sys, os

def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    problems = 0

    # --- Phase 1: Orphan keys (code references but no definition) --- HARD FAIL
    values_dir = os.path.join(root, "src/android/app/src/main/res/values")
    strings_xml = os.path.join(values_dir, "strings.xml")
    if not os.path.exists(strings_xml):
        print("!! strings.xml not found at", strings_xml)
        return 1

    with open(strings_xml, encoding="utf-8") as f:
        content = f.read()
    defined_keys = set(re.findall(r'name="([a-z0-9_]+)"', content))

    code_keys = set()
    src_dir = os.path.join(root, "src/android/app/src/main")
    for dirpath, _, filenames in os.walk(src_dir):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            with open(os.path.join(dirpath, fn), encoding="utf-8") as f:
                code_keys.update(re.findall(r'R\.string\.([a-z0-9_]+)', f.read()))

    orphans = code_keys - defined_keys
    if orphans:
        problems += 1
        print(f"❌ Orphan string keys (referenced in code but NOT in strings.xml):")
        for k in sorted(orphans):
            print(f"   R.string.{k}")
    else:
        print(f"✅ No orphan string keys ({len(code_keys)} code refs, {len(defined_keys)} definitions)")

    # --- Phase 2: Missing translations --- WARNING ONLY
    res_dir = os.path.join(root, "src/android/app/src/main/res")
    locale_dirs = sorted(d for d in os.listdir(res_dir) if d.startswith("values-") and d != "values")
    for loc in locale_dirs:
        loc_path = os.path.join(res_dir, loc, "strings.xml")
        if not os.path.exists(loc_path):
            continue
        with open(loc_path, encoding="utf-8") as f:
            loc_keys = set(re.findall(r'name="([a-z0-9_]+)"', f.read()))
        missing = (defined_keys - loc_keys) & code_keys
        if missing:
            print(f"⚠️  {loc}: {len(missing)} active keys missing translation (WARNING)")

    print(f"\n{'='*60}")
    print(f"Done: {'ORPHAN KEYS FOUND: ' + str(problems) if problems else 'ALL CLEAN ✅'}")
    return 1 if problems else 0

if __name__ == "__main__":
    sys.exit(main())