package com.piercingxx.xxnote.core

/**
 * The §6 truth table (design.md §6): given the last-agreed [BaseSnapshot] and
 * both sides' current [NoteState], decide the one [Verdict] for this note.
 *
 * Pure by charter (design §5): sees three content snapshots, returns a verdict,
 * performs no I/O, imports nothing from `android.*`, and contains no clock —
 * there is no timestamp comparison anywhere in this file (todo rule #3).
 * Totality is a requirement, not an aspiration: every representable
 * (base, local, remote) combination resolves below, always toward preservation
 * (todo rule #2). Deletes go to trash; conflicts fork; edits outrank deletes.
 *
 * Vocabulary used below:
 *  - **absent** — `NoteState.body == null`.
 *  - **live** — present and not trashed.
 *  - **dirty** — body differs from `base.body` (string equality here; hashing
 *    is the engine's business per core/README.md). `body` is WHOLE-FILE text —
 *    YAML frontmatter block plus Markdown body, exactly the bytes of the .md
 *    file — so a labels/pin/color-only edit dirties the snapshot and
 *    propagates, rather than evaluating clean+clean → Nothing.
 *  - **remote gone from live vault** — absent, OR trashed: a remote note in
 *    `.xxnote/trash/` counts as gone from the live vault (core/README.md);
 *    `remote.trashed` mirrors that when a trash listing is available. The
 *    trashed copy still holds its bytes — trash never unlinks (D9).
 *
 * Evaluation order — exactly §6 rows 1–12 read top to bottom under that
 * vocabulary, with the pinned totality corners inserted where the rows are
 * silent. Rows are mutually exclusive once "clean/dirty" excludes trashed
 * locals and remote-trashed normalizes to gone:
 *
 *  - **Corner T0** (totality, task-pinned): local absent + remote holding only
 *    a trashed copy → [Verdict.Trash]. The trash copy is the sole holder of
 *    bytes on either side; the verdict confirms trash state without unlinking.
 *    This is the mirror face of row 9: the note belongs in no live vault.
 *  - **No base** (`base == null`):
 *    - both live, differing bodies → **Fork** (row 11: two files claim one id);
 *    - both live, equal bodies → **Nothing** (corner: engine adopts as base);
 *    - local live, remote gone-from-live → **Push** (row 2; a trashed remote
 *      copy stays untouched server-side, D9);
 *    - local trashed, remote live → **Fork** (corner: two claims on one id);
 *    - local absent, remote live → **Pull** (row 1);
 *    - anything else (both absent; trashed local with gone/trashed remote) →
 *      **Nothing** (corners: nothing lives anywhere, every copy already rests
 *      in some trash).
 *  - **Base exists**:
 *    - local trashed: remote gone or trashed → **Nothing** (corner: deletion
 *      agreed, both copies already retained); remote dirty → **Resurrect**
 *      (row 8); remote clean → **Trash** (row 7);
 *    - local absent: remote gone-from-live → **Nothing**, else → **Pull**
 *      (ruling beyond the README: a known base with a vanished local mirror is
 *      an anomaly — vault is truth, todo rule #4 — so re-fetch rather than
 *      treat it as a delete; corner T0 has already intercepted a trashed
 *      remote, so "gone" here means truly absent);
 *    - local live: remote gone-from-live → local dirty ? **Resurrect** :
 *      **Trash** (rows 10/9); then rows 3/4/5/6: clean+clean → **Nothing**,
 *      dirty+clean → **Push**, clean+dirty → **Pull**, dirty+dirty →
 *      **Merge**.
 *
 * Row 12 never runs here: an `If-Match` rejection mid-push is an engine event,
 * surfaced through [decidePushRejection].
 */
object SyncPolicy {

    /**
     * Decide the verdict for one note from its three snapshots. Total over all
     * inputs; throws nothing; touches nothing.
     */
    fun decide(base: BaseSnapshot?, local: NoteState, remote: NoteState): Verdict {
        // Corner T0 — must precede gone-normalization: the remote trash copy is
        // the only bytes-holder left, and the answer is Trash (never unlink, D9),
        // regardless of base existence.
        if (!local.present && remote.present && remote.trashed) return Verdict.Trash

        if (base == null) {
            val localLive = local.present && !local.trashed
            val remoteLive = remote.present && !remote.trashed
            return when {
                localLive && remoteLive ->
                    if (local.body != remote.body) Verdict.Fork else Verdict.Nothing
                localLive -> Verdict.Push
                remoteLive ->
                    if (local.trashed) Verdict.Fork else Verdict.Pull
                else -> Verdict.Nothing
            }
        }

        val localDirty = local.body != base.body
        val remoteDirty = remote.body != base.body
        val remoteGone = !remote.present || remote.trashed

        return when {
            local.trashed -> when {
                !remote.present || remote.trashed -> Verdict.Nothing
                remoteDirty -> Verdict.Resurrect
                else -> Verdict.Trash
            }
            !local.present -> if (remoteGone) Verdict.Nothing else Verdict.Pull
            remoteGone -> if (localDirty) Verdict.Resurrect else Verdict.Trash
            !localDirty && !remoteDirty -> Verdict.Nothing
            localDirty && !remoteDirty -> Verdict.Push
            !localDirty && remoteDirty -> Verdict.Pull
            else -> Verdict.Merge
        }
    }

    /**
     * Row 12: the engine's push raced — `If-Match` was rejected mid-push. Re-read
     * the remote and re-enter at row 1 ([decide]) for up to three rounds; past
     * that the ambiguity is terminal and forks rather than guessing. Pure step
     * counting: no timestamps (todo rule #3), no state.
     */
    fun decidePushRejection(roundsCompleted: Int): Verdict =
        if (roundsCompleted < 3) Verdict.Replan else Verdict.Fork
}
