package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] A misspelled tool name must not fail
 * SILENTLY.
 *
 * The dangerous case is a PARTIALLY wrong list: "all filtered out"
 * caught a wholly wrong allowlist, but one wrong name in a list of five
 * just quietly dropped one tool — and the agent looked healthy while
 * missing a capability the author had granted it.
 */
class SubagentAllowedToolsTypoTest {

    private val known = listOf("file_read", "file_write", "shell_execute", "browser_use")

    @Test
    fun `a correct allowlist has no unknown names`() {
        assertTrue(
            SubagentAllowedTools.unknownAllowedTools(
                setOf("file_read", "shell_execute"),
                known,
            ).isEmpty(),
        )
    }

    @Test
    fun `a misspelled tool name is reported`() {
        assertEquals(
            listOf("shell-execute"),
            SubagentAllowedTools.unknownAllowedTools(
                setOf("file_read", "shell-execute"),
                known,
            ),
        )
    }

    @Test
    fun `a case mismatch is reported too`() {
        // The runtime's grant check is EXACT, so "File_Read" grants
        // nothing. It must therefore be reported rather than silently
        // dropped.
        assertEquals(
            listOf("File_Read"),
            SubagentAllowedTools.unknownAllowedTools(setOf("File_Read"), known),
        )
    }

    /**
     * A tool that is registered only under some conditions (read_image
     * needs a model with image input) must still be a LEGAL name. The
     * check runs against the names the APP defines, not the names this
     * session happens to have — otherwise a skill that lists
     * `read_image` would be reported as broken on a text-only model, and
     * the spawn would be refused.
     */
    @Test
    fun `a conditionally registered tool is still a legal name`() {
        assertTrue(
            "read_image must be a legal tool name",
            SubagentAllowedTools.knownToolNames().contains("read_image"),
        )
        assertTrue(
            SubagentAllowedTools.unknownAllowedTools(
                setOf("file_read", "read_image", "browser_use"),
                SubagentAllowedTools.knownToolNames(),
            ).isEmpty(),
        )
    }

    /**
     * The installed `read-only-researcher` skill lists exactly these
     * three. It must never be reported as broken.
     */
    @Test
    fun `the installed read-only researcher skill is valid`() {
        assertTrue(
            SubagentAllowedTools.unknownAllowedTools(
                setOf("file_read", "read_image", "browser_use"),
                SubagentAllowedTools.knownToolNames(),
            ).isEmpty(),
        )
    }

    @Test
    fun `a null allowlist has no unknown names`() {
        // Null means "no allowlist" — everything the catalogue grants is
        // available. There is nothing to be wrong about.
        assertTrue(
            SubagentAllowedTools.unknownAllowedTools(null, known).isEmpty(),
        )
    }

    @Test
    fun `an empty allowlist has no unknown names`() {
        // Empty means "no tools at all" — a legitimate, if unusual,
        // configuration. The all-filtered-out check handles it.
        assertTrue(
            SubagentAllowedTools.unknownAllowedTools(emptySet(), known).isEmpty(),
        )
    }
}
