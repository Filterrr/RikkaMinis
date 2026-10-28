#!/bin/sh
# Negative verification for the max_turns: 75 change.
#
# Each case reintroduces ONE defect, asserts the guard goes RED, then
# restores the file. A guard that has never been seen to fail is not a
# guard — it is decoration.
#
# The restore MUST happen even when the assertion fails, or a later case
# runs against a polluted tree and the whole result becomes meaningless.

set -u
REPO=/var/minis/workspace/RikkaMinis
cd "$REPO" || exit 2

GEN=src/android/app/src/main/assets/skills/general-agent/SKILL.md
RO=src/android/app/src/main/assets/skills/read-only-researcher/SKILL.md
SKILL=src/android/app/src/main/java/com/openminis/app/tools/SubagentSkill.kt
RUNNER=src/android/app/src/main/java/com/openminis/app/tools/SubagentRunner.kt
TEST=src/android/app/src/test/java/com/openminis/app/tools/SubagentTurnBudgetWiringTest.kt

PASS=0
FAIL=0

restore() {
  for f in "$GEN" "$RO" "$SKILL" "$RUNNER"; do
    [ -f "$f.orig" ] && mv "$f.orig" "$f"
  done
}
trap restore EXIT INT TERM

# neg <label> <file> <python-replacement-script>
neg() {
  label=$1
  target=$2
  script=$3
  cp "$target" "$target.orig"
  python3 - "$target" "$script" <<'PY'
import sys
path, mode = sys.argv[1], sys.argv[2]
s = open(path, encoding="utf-8").read()
if mode == "turns40":
    s = s.replace("max_turns: 75", "max_turns: 40")
elif mode == "timeout900":
    s = s.replace("const val DEFAULT_TIMEOUT_SECONDS = 1800",
                  "const val DEFAULT_TIMEOUT_SECONDS = 900")
elif mode == "version":
    s = s.replace("version: 1.5.0", "version: 1.4.6")
elif mode == "version_ro":
    s = s.replace("version: 1.1.0", "version: 1.0.4")
elif mode == "unwire":
    s = s.replace("SubagentSkill.wallClockSecondsForTurnBudget(config.maxTurns)",
                  "null")
elif mode == "constant":
    s = s.replace("const val SHIPPED_SUBAGENT_MAX_TURNS = 75",
                  "const val SHIPPED_SUBAGENT_MAX_TURNS = 40")
else:
    raise SystemExit("unknown mode " + mode)
open(path, "w", encoding="utf-8").write(s)
PY
  out=$(timeout 600 sh scripts/run_subagent_tests.sh \
      com.openminis.app.tools.SubagentTurnBudgetWiringTest 2>&1)
  mv "$target.orig" "$target"
  if echo "$out" | grep -q "OK ("; then
    echo "FAIL  $label — guard stayed GREEN (the defect was not detected)"
    FAIL=$((FAIL + 1))
  else
    failed=$(echo "$out" | grep -cE "^([0-9]+\))|Tests run:.*Failures: [1-9]")
    echo "PASS  $label — guard went RED"
    PASS=$((PASS + 1))
  fi
}

echo "── negative verification: each defect must turn the guard RED ──"
neg "max_turns reverted to 40 in general-agent"   "$GEN"   turns40
neg "max_turns reverted to 40 in read-only-researcher" "$RO" turns40
neg "wall-clock default reverted to 900s"          "$SKILL" timeout900
neg "general-agent version not bumped"             "$GEN"   version
neg "read-only version not bumped"                 "$RO"    version_ro
neg "spawn-site wiring removed"                    "$RUNNER" unwire
neg "shipped constant reverted to 40"              "$SKILL" constant

echo
echo "negative verification: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
