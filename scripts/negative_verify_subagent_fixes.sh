#!/bin/sh
# Negative verification for the [fix/subagent-*] batch.
#
# Each case reverts ONE fix in the production source, runs the ONE test class
# that pins it, and requires a NON-ZERO exit. The file is restored on EVERY
# exit path including SIGINT/SIGTERM: a leftover patch poisons every later
# case, and a run interrupted mid-case leaves the tree silently broken (this
# has bitten this exact script once already).
set -u
REPO=/var/minis/workspace/RikkaMinis
SRC=$REPO/src/android/app/src/main/java/com/openminis/app/tools
PASS=0
FAIL=0
CURRENT=""
CURRENT_TMP=""

# ── self-heal any leftover from a previous killed run ────────────────────
# A SIGKILL from the sandbox does not run the trap, so the next invocation
# must clean up before it starts. Without this, one killed run leaves the tree
# patched and every later verdict is garbage.
for leftover in "$SRC"/*.negv-orig; do
    [ -f "$leftover" ] || continue
    target=${leftover%.negv-orig}
    echo "  ! recovering leftover patch: $(basename "$target")"
    mv -f "$leftover" "$target"
done

# ── guarantee restoration ────────────────────────────────────────────────
restore_current() {
    if [ -n "$CURRENT" ] && [ -f "$CURRENT_TMP" ]; then
        mv -f "$CURRENT_TMP" "$CURRENT"
    fi
    CURRENT=""
    CURRENT_TMP=""
}
trap 'restore_current; echo; echo "ABORTED — restored $CURRENT"' INT TERM HUP
trap 'restore_current' EXIT

patch_and_test() {
    name=$1; file=$2; cls=$3; old=$4; new=$5
    CURRENT=$file
    CURRENT_TMP=$file.negv-orig
    cp "$file" "$CURRENT_TMP"

    python3 - "$file" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
src = open(path, encoding='utf-8').read()
if old not in src:
    sys.stderr.write("ANCHOR_MISS\n")
    sys.exit(3)
open(path, 'w', encoding='utf-8').write(src.replace(old, new, 1))
PY
    if [ $? -ne 0 ]; then
        echo "  ✗ $name : anchor missed"
        restore_current; FAIL=$((FAIL+1)); return
    fi

    ( cd "$REPO" && sh scripts/run_subagent_tests.sh "$cls" >/tmp/negv_out.txt 2>&1 )
    rc=$?
    if [ $rc -ne 0 ]; then
        echo "  ✓ $name : RED"
        PASS=$((PASS+1))
    else
        echo "  ✗ $name : STAYED GREEN"
        FAIL=$((FAIL+1))
    fi
    restore_current

    # Prove the restore actually happened — a silent failure here is the
    # whole hazard this trap exists for.
    if [ -f "$CURRENT_TMP" ]; then
        echo "  ✗ $name : RESTORE FAILED — $CURRENT_TMP still on disk"
        FAIL=$((FAIL+1))
    fi
}

GROUP="${1:-all}"
want() { [ "$GROUP" = "all" ] || [ "$GROUP" = "$1" ]; }
# Run a case only when its group is selected. A function rather than an
# inline test because the arguments span several lines.
run() { g=$1; shift; if want "$g"; then "$@"; fi; }

echo "━━━ negative verification (group: $GROUP) ━━━"
want A && echo "A. envelope facts"
run A patch_and_test "A1 turns come from the outcome" "$SRC/SubagentParentDelivery.kt" SubagentEnvelopeFactsTest \
  'turns = if (outcome.turns > 0) outcome.turns else snapshot?.turn ?: 0' \
  'turns = snapshot?.turn ?: 0'
run A patch_and_test "A2 wait_any reads runtimeStatus" "$SRC/SubagentResultEnvelope.kt" SubagentEnvelopeFactsTest \
  'Framing.WAIT_ANY ->
            "wait_any: first completion -> run ${facts.runId} (${facts.skillName}) " +
                SubagentRunAssessment.summaryStatus(
                    // [fix/subagent-envelope-facts] This was a hardcoded
                    // `null`, so CANCELLED could never be rendered on the
                    // race path even though the facts carried it. Both
                    // detached framings now read the same field.
                    runtimeStatus = facts.runtimeStatus,' \
  'Framing.WAIT_ANY ->
            "wait_any: first completion -> run ${facts.runId} (${facts.skillName}) " +
                SubagentRunAssessment.summaryStatus(
                    runtimeStatus = null,'
run A patch_and_test "A3 parsed report on every terminal write" "$SRC/SubagentRunRegistry.kt" SubagentEnvelopeFactsTest \
  'parsedReport = run.parsedReport ?: parsedReportFor(delivered, source),
                )
            }
        }
        return applied' \
  'parsedReport = run.parsedReport,
                )
            }
        }
        return applied'

want B && echo "B. delivery shape"
run B patch_and_test "B2 inline envelope names the run" "$SRC/SubagentResult.kt" SubagentEnvelopeFactsTest \
  'runId = runId.ifBlank { skillId },' 'runId = skillId,'
run B patch_and_test "B3 summary is bounded" "$SRC/SubagentResultEnvelope.kt" SubagentContractBudgetTest \
  'val body = boundField("summary", summary, MAX_SUMMARY_CHARS)' 'val body = summary'
run B patch_and_test "B3b gaps is bounded" "$SRC/SubagentResultEnvelope.kt" SubagentContractBudgetTest \
  'return "gaps: " + boundField("gaps", gaps, MAX_GAPS_CHARS)' 'return "gaps: $gaps"'
run B patch_and_test "B3c findings list is bounded" "$SRC/SubagentResultEnvelope.kt" SubagentContractBudgetTest \
  'val shown = findings.take(MAX_FINDINGS_SHOWN)' 'val shown = findings'

want C && echo "C. parser"
run C patch_and_test "C1 status body kept as prose" "$SRC/SubagentReportContract.kt" SubagentReportProseLossTest \
  '                    } else if (line.isNotBlank()) {
                        proseLines += line.trim()' \
  '                    } else if (false) {
                        proseLines += line.trim()'
run C patch_and_test "C1b scalar field ends at a blank line" "$SRC/SubagentReportContract.kt" SubagentReportProseLossTest \
  'if (line.isBlank() && (current == FIELD_SUMMARY || current == FIELD_GAPS)) {' \
  'if (false) {'
run C patch_and_test "C2 a sentence is not a path" "$SRC/SubagentReportContract.kt" SubagentReportProseLossTest \
  'if (text.any { it.isWhitespace() }) return false' 'if (false) return false'
run C patch_and_test "C2b none is not an artifact" "$SRC/SubagentReportContract.kt" SubagentReportProseLossTest \
  'if (NO_ARTIFACT_TOKENS.contains(text.lowercase())) return false' 'if (false) return false'

want D && echo "D. security"
run D patch_and_test "D1 fence markers are escaped" "$SRC/SubagentParentDelivery.kt" SubagentContractBudgetTest \
  'append(escapeFenceMarkers(envelope))' 'append(envelope)'

want E && echo "E. recovery route (navigability)"
run E patch_and_test "E1 index gives real line numbers" "$SRC/SubagentJournalIndex.kt" SubagentJournalIndexTest \
  'starts += sb.count { it == '"'"'\n'"'"' } + 1' 'starts += sb.count { it == '"'"'\n'"'"' } + 2'
run E patch_and_test "E2 report precedes transcript in the journal" "$SRC/SubagentRunJournal.kt" SubagentJournalIndexTest \
  '"Report" to if (resultText.isBlank()) "(no text output produced)" else scrub(resultText),
            "Transcript" to renderTranscript(run, scrub),' \
  '"Transcript" to renderTranscript(run, scrub),
            "Report" to if (resultText.isBlank()) "(no text output produced)" else scrub(resultText),'
run E patch_and_test "E3 pointer names the conclusion range" "$SRC/SubagentResultEnvelope.kt" SubagentRecoveryRouteTest \
  'if (report != null && !report.isEmpty) {' 'if (false) {'
run E patch_and_test "E4 registry bound keeps the head" "$SRC/SubagentRunRegistry.kt" SubagentRecoveryRouteTest \
  'val head = text.take(SubagentRunRegistry.RESULT_TEXT_HEAD_CHARS)' 'val head = ""'
run E patch_and_test "E5 artifact names its size" "$SRC/SubagentResultEnvelope.kt" SubagentRecoveryRouteTest \
  'if (bytes != null && bytes >= 0) {' 'if (false) {'

echo
echo "passed: $PASS   failed: $FAIL"
[ $FAIL -eq 0 ] || exit 1