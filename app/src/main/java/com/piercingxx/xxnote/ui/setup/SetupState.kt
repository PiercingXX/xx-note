package com.piercingxx.xxnote.ui.setup

/**
 * The whole wizard as one immutable snapshot: which step, the fields typed
 * so far, whether a network action is in flight, and the current step's
 * words. The DSM password is deliberately NOT carried here past step 2 —
 * [SetupViewModel] wipes it the moment it is sealed or refused (§15/R9),
 * so a half-configured sync can never look configured with a live secret
 * sitting in recomposition state.
 */
data class SetupState(
    val step: SetupStep = SetupStep.HOST,
    // step 1 — host + port (HTTPS only)
    val host: String = "",
    val port: String = SetupLogic.DEFAULT_PORT.toString(),
    // step 2 — account
    val user: String = "",
    /** Cleared on leaving the step; blank + held secret means "unchanged". */
    val password: String = "",
    // step 3 — test result lines (verbatim first, plain words second)
    val probeLines: List<String> = emptyList(),
    // step 4 — browse rows and the typed new-folder field
    val folderRows: List<FolderRow> = emptyList(),
    val newFolder: String = "",
    val pickedPath: String? = null,
    // step 5 — confirm counts + disclosure
    val foundMd: Int = 0,
    val idLessMd: Int = 0,
    val etagMode: String = SetupLogic.ETAG_MODE_FALLBACK,
    // step 6 — device name
    val deviceName: String = "",
    // cross-cutting
    val busy: Boolean = false,
    /** R10 tone status lines for whatever step is showing. */
    val message: List<String> = emptyList(),
    // step 7 — first sync outcome lines; done gates the final button
    val syncLines: List<String> = emptyList(),
    val done: Boolean = false,
)

enum class SetupStep(val index: Int, val label: String) {
    HOST(1, "host"),
    ACCOUNT(2, "account"),
    TEST(3, "test"),
    FOLDER(4, "folder"),
    CONFIRM(5, "confirm"),
    DEVICE(6, "device name"),
    FIRST_SYNC(7, "first sync"),
}

/** One §4.2 candidate prefix as step 4 renders it. */
data class FolderRow(
    val path: String,
    val reachable: Boolean,
    val mdCount: Int,
    /** Plain words for the non-reachable cases ("HTTP 404", unreachable…). */
    val note: String,
)
