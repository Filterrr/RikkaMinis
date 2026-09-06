package com.openminis.app.ui.chat

/**
 * [T-message-fork-polish] Outcome of a fork attempt. Replaces the old
 * `String?` return so the UI can tell the user WHY a fork didn't happen
 * instead of a single opaque "fork failed" toast.
 */
internal sealed class ForkResult {
    /** Fork committed — carries the new session id for navigation. */
    data class Success(val forkSid: String) : ForkResult()

    /** Stream in flight; the caller's menu should have been gated already. */
    data object Streaming : ForkResult()

    /** Context compaction running; retry after it settles. */
    data object Compacting : ForkResult()

    /** Draft session (nothing sent yet) or anchor rows missing from the DB. */
    data object NothingToCopy : ForkResult()

    /** Source session row gone (deleted concurrently) or DB write failed. */
    data class DbError(val reason: String?) : ForkResult()

    /** True when the caller may navigate to a new session. */
    val isSuccess: Boolean get() = this is Success
}

/**
 * [T-message-fork-polish] How much history the fork carries.
 *
 *  - [All]: every row up to the anchor (original semantics — the fork's
 *    model context matches the original session's exactly).
 *  - [LastTurns]: only the trailing [turns] user-turn boundaries (the
 *    anchor's turn is always included). For heavyweight sessions where the
 *    user wants a light branch, not a full-history replica.
 */
internal sealed class ForkScope {
    data object All : ForkScope()
    data class LastTurns(val turns: Int) : ForkScope() {
        init { require(turns >= 1) { "LastTurns requires turns >= 1" } }
    }

    internal companion object {
        const val ALL_MARKER = "all"
    }
}
