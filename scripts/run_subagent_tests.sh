#!/bin/sh
# Compile and run the sub-agent JVM unit tests WITHOUT Gradle.
#
# Why this exists: the sub-agent subsystem's pure-logic classes are the
# guard rail for every runtime fix, but the full Android build is slow and
# CI is the only feedback loop for the Android-dependent files. This script
# compiles the PURE classes (no Context / no provider gateway) plus their
# tests, and runs them on the plain JVM — fast enough to iterate.
#
# The Android-dependent files (SubagentRunner.kt, ChatViewModel.kt) are NOT
# compilable here; they are covered by CI's "Run unit tests (full suite)"
# and by the source-grep wiring guards — which is exactly why the pure
# logic was moved out of them in the first place.
#
# Usage:  sh scripts/run_subagent_tests.sh [TestClass ...]
#         (no args = run every *Subagent*Test / Subagent*Test)

set -e

REPO=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SRC=$REPO/src/android/app/src/main/java/com/openminis/app
TEST=$REPO/src/android/app/src/test/java/com/openminis/app
# The resolver walks from the PACKAGE root so it can read the package
# declaration out of each file.
TEST_ROOT=$REPO/src/android/app/src/test/java
OUT=/tmp/subagent-tests-out

KOTLINC=/tmp/kotlinc/bin/kotlinc
if [ ! -x "$KOTLINC" ]; then
  echo "kotlinc not found at $KOTLINC" >&2
  exit 1
fi

jar1() { find /root/.gradle/caches -name "$1" 2>/dev/null | head -1; }

JSON=$(jar1 'json-20231013.jar')
COR=$(jar1 'kotlinx-coroutines-core-jvm-1.9.0.jar')
SER=$(jar1 'kotlinx-serialization-core-jvm-*.jar')
SERJ=$(jar1 'kotlinx-serialization-json-jvm-*.jar')
JUNIT=$(jar1 'junit-4.13.2.jar')
HAM=$(jar1 'hamcrest-core-1.3.jar')

ANDROID=$(find /opt/android-sdk /root -name 'android.jar' 2>/dev/null | sort -V | tail -1)
if [ -z "$ANDROID" ]; then echo "android.jar not found" >&2; exit 1; fi

CP="$JSON:$COR:$SER:$SERJ:$JUNIT:$HAM:$ANDROID:/tmp/kotlinc/lib/kotlin-stdlib.jar:/tmp/kotlinc/lib/kotlin-stdlib-jdk8.jar:/tmp/kotlinc/lib/kotlin-stdlib-jdk7.jar"

rm -rf "$OUT"
mkdir -p "$OUT"

# ── Production sources (pure only) ───────────────────────────────────────
# Only files whose whole transitive dependency set is JVM-pure are listed
# here. A file that pulls in android.* is deliberately absent: it is
# covered by CI, not by this script.
PROD=""
for f in \
  data/model/AgentToolDefinition.kt \
  data/model/ThinkingLevel.kt \
  data/model/LLMUsage.kt \
  data/model/LLMMessage.kt \
  data/model/AgentContentPart.kt \
  provider/RequestSanitizers.kt \
  tools/SubagentSkill.kt \
  tools/AgentCapabilities.kt \
  tools/SubagentAllowedTools.kt \
  tools/SubagentModelResolver.kt \
  tools/SubagentModelArg.kt \
  tools/SubagentGroupSpread.kt \
  tools/SubagentPlanContract.kt \
  tools/SubagentPlanGate.kt \
  tools/SubagentReportHygiene.kt \
  tools/SubagentReportDecision.kt \
  tools/SubagentRunAssessment.kt \
  tools/SubagentResult.kt \
  tools/SubagentParentDelivery.kt \
  tools/SubagentStepOutputTrim.kt \
  tools/SubagentToolResultBatch.kt \
  tools/SubagentBudgetClock.kt \
  tools/SubagentBudgetGuard.kt \
  tools/SubagentHistoryBudget.kt \
  tools/SubagentHistoryRepair.kt \
  tools/SubagentExecutedCallCount.kt \
  tools/SubagentOrchestration.kt \
  tools/SubagentOrchestrationTools.kt \
  tools/SubagentApproval.kt \
  tools/SubagentScheduler.kt \
  tools/SubagentAnsiText.kt \
  tools/SubagentArtifactTruth.kt \
  tools/SubagentRunRegistry.kt \
  tools/SubagentRunFormat.kt \
  tools/SubagentContextTokens.kt \
  tools/SubagentCheckpointFormat.kt \
  tools/SubagentRosterHint.kt \
  tools/SubagentFrontmatter.kt \
  tools/SubagentCostLedger.kt \
  tools/SubagentToolScope.kt \
  tools/SubagentTaskFingerprint.kt \
  tools/SubagentDelegation.kt \
  tools/SubagentPartialRecovery.kt \
  notification/NotificationDecisionRouter.kt \
  ; do
  if [ -f "$SRC/$f" ]; then PROD="$PROD $SRC/$f"; fi
done

# ── Test sources ─────────────────────────────────────────────────────────
# The SOURCE selection and the CLASS selection must agree. A file may
# declare more than one test class (SubagentRunRegistryTest.kt also
# declares SubagentSchedulerTest), so resolving a class by filename
# silently dropped the extra class from the run — a test the CI failed on
# was GREEN locally. Both sides now go through the same resolver: the
# class name maps to the file that DECLARES it, and the default run
# takes every file that declares a Subagent test class.
RESOLVER="$(dirname "$0")/resolve_test_classes.py"

if [ $# -gt 0 ]; then
  TESTS=""
  for c in "$@"; do
    f=$(python3 "$RESOLVER" --file "$TEST_ROOT" "$c") || {
      echo "test class not found: $c" >&2; exit 1;
    }
    TESTS="$TESTS $f"
  done
else
  TESTS=$(python3 "$RESOLVER" --files "$TEST_ROOT" | sort)
fi

# The source-grep drift guards need the production tree on disk, and
# ProductionSources is a TEST helper — it lives beside the tests.
# The Android-dependent pure-ish classes: their logic is pure, but the
# FILES import android.* — so they compile against android.jar. The one
# genuinely Android-only collaborator (EnvVarRepository) is stubbed.
ANDROIDISH=""
for f in \
  data/EnvVarRedactor.kt \
  data/EnvVarPrivacyStore.kt \
  tools/SubagentJournalRedaction.kt \
  ; do
  if [ -f "$SRC/$f" ]; then ANDROIDISH="$ANDROIDISH $SRC/$f"; fi
done

# The JVM heap for kotlinc matters: adding the scheduler test class
# pushed the compile over the default heap and it died with
# "OutOfMemoryError: Java heap space" mid-analysis. 3g is comfortable on
# a 15g host and leaves room for the test JVM.
"$KOTLINC" -J-Xmx3g -nowarn -classpath "$CP" -d "$OUT" $PROD $ANDROIDISH \
  "$REPO/scripts/test-stubs/EnvVarRepositoryStub.kt" $TESTS "$TEST/ProductionSources.kt"

# ── Run ──────────────────────────────────────────────────────────────────
# The class list is derived from the DECLARED class names, not the file
# names. A file may declare more than one test class (SubagentRunRegistryTest.kt
# also declares SubagentSchedulerTest), and resolving by filename silently
# dropped the extra class from the run — so a test the CI failed on was
# GREEN locally. That is the worst kind of gap: it hides a failure exactly
# when the failure is real.
if [ $# -gt 0 ]; then
  CLASSES=""
  for c in "$@"; do
    resolved=$(python3 "$RESOLVER" "$TEST_ROOT" "$c") || {
      echo "test class not found: $c" >&2
      exit 1
    }
    CLASSES="$CLASSES $resolved"
  done
else
  CLASSES=$(python3 "$RESOLVER" --all "$TEST_ROOT")
fi

java -cp "$OUT:$CP" org.junit.runner.JUnitCore $CLASSES
