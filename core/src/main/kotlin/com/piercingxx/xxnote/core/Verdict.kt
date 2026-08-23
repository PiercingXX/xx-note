package com.piercingxx.xxnote.core

/**
 * What should happen to one note (design §6). First match wins, evaluated by
 * [SyncPolicy.decide]. Pure data — applying a verdict is SyncEngine's job
 * (WS5), never this module's.
 */
enum class Verdict {
    /** Remote holds content the local side lacks or trails; fetch it. Rows 1, 5. */
    Pull,

    /** Local holds content the server has never seen or is stale; write it up. Rows 2, 4. */
    Push,

    /** Both sides moved off base; attempt three-way merge, push the result. Row 6. */
    Merge,

    /** Keep both as two visible notes: unmergeable hunks, ambiguous pairing,
     *  two files claiming one id, or replan rounds exhausted. Rows 6-refusal, 11, 12-terminal. */
    Fork,

    /** Exactly one side still holds the live note; that side moves it to trash.
     *  Never an unlink (D9). Rows 7, 9. Also the T0 confirmation corner
     *  (local absent + remote holding only a trashed copy): no side holds a
     *  live copy and the live vault is already correct, so nothing moves —
     *  the engine treats it as Nothing-equivalent bookkeeping. */
    Trash,

    /** An edit outranks a delete; the live note comes back. Rows 8, 10. */
    Resurrect,

    /** Agreement, or nothing left to reconcile. Row 3 and fully-deleted corners. */
    Nothing,

    /** Push raced (`If-Match` rejected mid-push); re-read remote, re-enter at row 1.
     *  Bounded to 3 rounds, then Fork. Row 12. */
    Replan,
}
