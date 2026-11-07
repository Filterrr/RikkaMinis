package com.openminis.app.data.session

/**
 * [port/upstream-subagent-child-session] Parent/child session graph helpers.
 *
 * A sub-agent run can now persist as a HIDDEN CHILD SESSION of the chat that
 * spawned it (`ChatSessionEntity.parentSessionId`). The moment that is true,
 * every operation that treats a session as a unit has to cover the whole
 * subtree rather than one level:
 *
 *  - deleting a chat must delete its child runs, or the rows survive with a
 *    dangling parent and `messages` cascade-delete never fires for them;
 *  - storage accounting that adds only direct children under-reports;
 *  - "release this chat's caches" must reach the children too.
 *
 * Hardcoding one hop is the bug class this exists to prevent: a child can
 * itself have children (a sub-agent cannot spawn today, but the storage and
 * delete paths must not encode that assumption twice).
 *
 * Pure functions over an id graph, so the walk is unit-testable without Room.
 * Ported from OpenMinis `data/session/SessionTree.kt`.
 */
object SessionTree {

    /**
     * Ids of every descendant of [rootId], deepest first, root excluded.
     *
     * Deepest-first is the DELETE order: a child row must go before its parent
     * so a crash mid-way never leaves an orphan pointing at a missing parent.
     * Cycle-safe — a corrupt parent link cannot loop forever.
     */
    suspend fun descendantsDeepestFirst(
        rootId: String,
        childrenOf: suspend (String) -> List<String>,
    ): List<String> {
        val seen = mutableSetOf(rootId)
        val order = mutableListOf<String>()
        suspend fun walk(id: String) {
            for (child in childrenOf(id)) {
                if (!seen.add(child)) continue
                walk(child)
                order += child
            }
        }
        walk(rootId)
        return order
    }

    /** [rootId] plus all descendants, deepest first (root last). */
    suspend fun subtreeDeepestFirst(
        rootId: String,
        childrenOf: suspend (String) -> List<String>,
    ): List<String> = descendantsDeepestFirst(rootId, childrenOf) + rootId

    /**
     * Resolve each session to its top-level ancestor given a parent map
     * (`id → parentId?`).
     *
     * A dangling parent (id not present in the map) is treated as the root, so
     * a child whose parent row was lost still surfaces in accounting instead of
     * vanishing from the total.
     */
    fun rootOf(id: String, parentOf: Map<String, String?>): String {
        var cur = id
        val seen = mutableSetOf(cur)
        while (true) {
            val p = parentOf[cur] ?: return cur
            if (p !in parentOf || !seen.add(p)) return cur
            cur = p
        }
    }

    /**
     * Aggregate per-session sizes onto their root ancestor.
     *
     * The result contains ONLY roots, every size counted exactly once — what a
     * storage screen should show for a chat that fanned out into five runs.
     */
    fun aggregateToRoots(
        parentOf: Map<String, String?>,
        sizes: Map<String, Long>,
    ): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        for (id in parentOf.keys) {
            val root = rootOf(id, parentOf)
            out[root] = (out[root] ?: 0L) + (sizes[id] ?: 0L)
        }
        return out
    }

    /** Ids in [parentOf] whose root is [rootId] (root included). */
    fun membersOf(rootId: String, parentOf: Map<String, String?>): List<String> =
        parentOf.keys.filter { rootOf(it, parentOf) == rootId }
}