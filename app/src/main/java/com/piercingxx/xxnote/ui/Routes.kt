package com.piercingxx.xxnote.ui

/**
 * Navigation contract — pinned so parallel screen workstreams cannot drift.
 * MainActivity hosts a NavHost over these routes; each screen file owns its
 * composable and nothing else.
 */
object Routes {
    const val GRID = "grid"
    const val EDITOR = "editor/{noteId}"
    const val SYNC = "sync"
    const val SETUP = "setup"
    const val ARCHIVE = "archive"
    const val TRASH = "trash"
    const val LABELS = "labels"
    const val LABEL = "label/{name}"

    fun editor(noteId: String) = "editor/$noteId"

    /**
     * H2 contract: [name] MUST be `Uri.encode`-ed by the CALLER before it
     * lands in the route pattern — labels may contain `/ ? # %` and spaces.
     * The destination's nav argument decodes automatically (exactly once).
     */
    fun label(name: String) = "label/$name"
}
