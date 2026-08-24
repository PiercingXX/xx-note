package com.piercingxx.xxnote.sync

import com.piercingxx.xxnote.core.BaseSnapshot
import com.piercingxx.xxnote.core.Diff3
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.NoteState
import com.piercingxx.xxnote.core.SyncPolicy
import com.piercingxx.xxnote.core.Ulid
import com.piercingxx.xxnote.core.Verdict
import com.piercingxx.xxnote.net.HttpError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * WS5 assembly (design §5): plan → apply → log over the pinned ports.
 *
 * The labor table is obeyed literally: this class sees both sides plus base
 * and applies [SyncPolicy] verdicts — it never invents one. Every resolution
 * decision below reads bodies and ETags only; the clock is touched solely for
 * `conflictAt:` stamps and fresh fork ids (todo rule #3). No `android.*`
 * imports; the engine is proven by plain JVM tests against port fakes.
 *
 * **Whole-file law.** Every string here is an ENTIRE `.md` file (Ports.kt):
 * YAML frontmatter block plus Markdown body, exactly the bytes on disk or
 * server. "Dirty" therefore means whole-file inequality against
 * [BaseSnapshot.body], per core/README.md.
 *
 * **Vault safety (§9).** The first pass computes every verdict before applying
 * anything. If the pass would trash more than [syncOnce]'s threshold of the
 * live vault in one go — almost always a misconfigured path or an empty share
 * mounted over the right one — it halts as [SyncOutcome.HaltedTrashSafety]
 * with nothing applied anywhere.
 *
 * **Fork-text ruling** (documented because §6/§7 leave the fork's bytes open):
 *
 * - A **prose-conflict** refusal (`MergeOutcome.Fork("prose conflict")`) forks
 *   with `Diff3.mergeWithMarkers(base, local, remote)` over the three BODY
 *   texts, assembled as a whole file: the local note's frontmatter block
 *   verbatim, followed by the marker-annotated body. Both sides' text sits in
 *   the file inside `<<<<<<<` / `=======` / `>>>>>>>` markers so the Resolve
 *   sheet (§7) can show the conflict without re-deriving it.
 * - Every other fork — checklist item conflicts, frontmatter-key conflicts,
 *   row 11 (two files claim one id), and replan exhaustion after 3 rounds —
 *   forks with **the side NOT already in the local mirror**: the remote text,
 *   since the mirror holds local in every flow this engine runs (the
 *   vice-versa clause is reserved for future pull-side flows). Both texts then
 *   exist on this device: the mirror keeps local under the original id, the
 *   fork carries remote under a fresh id.
 *
 * **Termination ruling** (the piece §7 does not spell out). After any fork,
 * the ORIGINAL note's base is advanced to the side the fork carried:
 * `recordBase(original, <fork-carried text>, <that side's etag>)`. The next
 * sync then sees local-dirty/remote-clean → row 4 → pushes the mirror's text
 * up under `If-Match`, guarded exactly like any push. Nothing is overwritten
 * blind: if another client wrote meanwhile, row 12 replans from the new
 * triple. The original converges to its side, the fork preserves the other
 * side, and the same conflict never re-forks forever. Without this the
 * untouched base would re-enter dirty+dirty every sync — Joplin's wall of
 * conflict copies, which §7 exists to prevent.
 *
 * **Outbox ruling.** A `FAILED` put enqueues `('put', payload=localText)` and
 * moves on — offline is a normal state (§15), not an error. Queued ops are
 * NEVER replayed blindly: the durable intent lives in base-vs-mirror
 * divergence, so each sync simply re-decides the note through §6 from scratch.
 * After the pass, a queued op whose payload equals the freshly recorded base
 * body has demonstrably landed and is drained (`markOpDone`); anything else is
 * marked failed-attempted and left queued.
 *
 * **Row-11 corner ruling.** When MORE THAN ONE remote file carries the same
 * parsed id, the listing-first claim represents the id for the §6 decision;
 * each additional claimant is re-identified IN PLACE: its own bytes are kept
 * byte-for-byte while its frontmatter gains a fresh id plus `conflictOf:` /
 * `conflictAt:` (conditional PUT against the claimant's own ETag — no blind
 * write, nothing discarded). Left alone, the duplicate would re-fork on every
 * future sync; re-identified, it becomes the distinct visible note row 11
 * promises. If the conditional write fails, the duplicate is left untouched
 * and will be retried next sync — failure direction: preserve.
 *
 * **Id-less remote files.** A `.md` whose frontmatter carries no usable ULID
 * cannot be matched by identity (D3) and is skipped with a flagged log entry;
 * assigning identity to foreign files is Setup's disclosed import step
 * (§12), never the engine's silent move.
 *
 * **Attachment upload ordering (§10 law).** An attachment file must land on
 * the server BEFORE the note body referencing it, so the far side never sees
 * a broken link. Before every body-writing remote call — Push, Merge,
 * fork-upload, and row-10 recreate (a ruling: resurrect is morally a push) —
 * the outgoing whole-file text is scanned with [AttachmentRefs] (the same
 * regex AttachmentStore sweeps with), and every referenced hash whose store
 * row says `remoteKnown=false` is uploaded first via [RemoteFiles.putFile].
 * A WRITTEN upload marks the row `remoteKnown=true`; a FAILED/PRECONDITION
 * upload (or a missing local cache file) DEFERS THE WHOLE NOTE PUSH: an
 * idempotent `('attach', <full-hash>)` op is enqueued for the outbox screen
 * and a log line names the reason. The note stays base-dirty, so the next
 * pass re-decides it through §6, retries the attachment first ("pending
 * 'attach' ops retry first"), and only pushes once every reference has
 * landed. Content addressing makes dedup free: the second note referencing
 * a hash finds `remoteKnown=true` and uploads nothing. When no
 * [Attachments] implementation is wired (legacy fakes/tests), ordering is
 * disabled entirely and bodies push as before.
 */
class SyncEngine(
    private val local: LocalFiles,
    private val remote: RemoteFiles,
    private val book: SyncBookkeeping,
    private val deviceName: String,
    private val clock: () -> Instant = { Instant.now() },
    /**
     * Optional §10 ordering seam ([data.AttachmentStore] in production; null
     * disables attachment ordering for legacy fakes/tests). Null means the
     * engine never scans texts or uploads bytes — bodies push unconditionally.
     */
    private val attachments: Attachments? = null,
    /**
     * Builds the [ConflictNamer] used for fork filenames. The default wires
     * `nameExists` to "the live remote listing knows the name OR the local
     * mirror already occupies it" ([LocalFiles.freeName] returns the name
     * unchanged exactly when it is free).
     */
    private val newNamer: (nameExists: (String) -> Boolean) -> ConflictNamer =
        { exists -> ConflictNamer(deviceName, clock, exists) },
) {

    /** What one [syncOnce] pass did, or why it stopped before doing anything. */
    sealed interface SyncOutcome {
        data class Completed(
            val pulled: Int,
            val pushed: Int,
            val merged: Int,
            val forked: Int,
            val trashed: Int,
            val resurrected: Int,
            val nothing: Int,
            /** D9 expiry: expired trash copies removed this pass, both sides. */
            val expired: Int = 0,
        ) : SyncOutcome

        data class HaltedTrashSafety(val wouldTrash: Int, val liveNotes: Int) : SyncOutcome

        /**
         * The far side refused us outright — 401/403 mean stale credentials.
         * The pass halts without enqueueing further work; retrying cannot
         * succeed until the operator re-authenticates, so this must never
         * become a retry loop (the worker maps it to success + stale mark).
         */
        data class AuthFailed(val status: Int) : SyncOutcome
    }

    // ---- per-sync run scope --------------------------------------------------

    private class Tally {
        var pulled = 0
        var pushed = 0
        var merged = 0
        var forked = 0
        var trashed = 0
        var resurrected = 0
        var nothing = 0
        var expired = 0
    }

    /** One remote file that parses to a usable note id. Whole-file text. */
    private class Claim(val path: String, val etag: String?, val text: String)

    /** Everything §6 needs about one note, frozen at plan time. */
    private class Plan(
        val id: String,
        val base: BaseSnapshot?,
        val localNote: LocalNote?,
        val remoteState: NoteState,
        val claims: List<Claim>,
        val verdict: Verdict,
    ) {
        val localState: NoteState =
            localNote?.let { NoteState(body = it.wholeFileText, trashed = it.trashed) }
                ?: NoteState(body = null, trashed = false)

        val localText: String? get() = localNote?.wholeFileText

        /** First claim stands for the id; extras are duplicate claimants. */
        val primary: Claim? get() = claims.firstOrNull()

        /** Push/fork target path: the mirror's path, else the far side's. */
        val workPath: String get() = localNote?.path ?: primary?.path ?: "$id-note.md"
    }

    private inner class Run {
        val tally = Tally()
        /** Live-vault names seen this pass; feeds fork-name uniqueness. */
        val remoteNames = HashSet<String>()
        /** Ids decided this pass — bounds outbox reconciliation. */
        val touchedIds = HashSet<String>()
    }

    // ---- the pass -------------------------------------------------------------

    /**
     * Serializes whole-vault passes. WorkManager does not serialize across
     * unique names: the periodic (`xx-note-sync-periodic`) and expedited
     * (`xx-note-sync-once`) chains can dispatch two [syncOnce] passes into
     * this process at once, and interleaved whole-vault reconciliations would
     * race the same mirror, database, and NAS. Overlapping invocations queue
     * here instead — one pass runs to completion before the next begins.
     */
    private val passMutex = Mutex()

    fun syncOnce(trashSafetyThreshold: Double = DEFAULT_TRASH_SAFETY_THRESHOLD): SyncOutcome =
        runBlocking { passMutex.withLock { runPass(trashSafetyThreshold) } }

    private fun runPass(trashSafetyThreshold: Double): SyncOutcome {
        val run = Run()
        val claimsById = try {
            fetchRemoteClaims(run)
        } catch (e: HttpError) {
            if (e.status == 401 || e.status == 403) return SyncOutcome.AuthFailed(e.status)
            throw e
        }

        val localById =
            (local.listLive() + local.listTrashed()).associateBy { it.id }

        val plans = (claimsById.keys + localById.keys)
            .toSortedSet()
            .mapNotNull { id ->
                val claims = claimsById[id].orEmpty()
                val localNote = localById[id]
                if (claims.isEmpty() && localNote == null) return@mapNotNull null
                val remoteState = claims.firstOrNull()
                    ?.let { NoteState(body = it.text, trashed = false) }
                    ?: NoteState(body = null, trashed = false)
                val base = book.baseOf(id)
                Plan(
                    id = id,
                    base = base,
                    localNote = localNote,
                    remoteState = remoteState,
                    claims = claims,
                    verdict = SyncPolicy.decide(base, localStateOf(localNote), remoteState),
                )
            }

        // FIRST PASS — all verdicts computed above; §9 gate before ANY apply.
        val liveNotes = plans.count { it.localState.present && !it.localState.trashed }
        val wouldTrash = plans.count { it.verdict == Verdict.Trash && trashRemovesLiveCopy(it) }
        if (liveNotes > 0 && wouldTrash > trashSafetyThreshold * liveNotes) {
            book.log(
                SyncLogEntry(
                    noteId = null,
                    verdict = "HaltedTrashSafety",
                    reason = "would trash $wouldTrash of $liveNotes live notes (> ${100 * trashSafetyThreshold}%)",
                    ok = false,
                ),
            )
            return SyncOutcome.HaltedTrashSafety(wouldTrash, liveNotes)
        }

        // SECOND PASS — apply verdicts, id-ordered for determinism. The trash
        // skeleton is ensured once per pass, after the §9 gate (a halted pass
        // applies nothing anywhere); already-exists is success (M2).
        ensureTrashSkeleton()
        try {
            for (plan in plans) {
                run.touchedIds += plan.id
                process(plan, run)
                if (plan.claims.size > 1) reidentifyDuplicateClaims(plan, run)
            }

            reconcileOutbox(run)
            expireStaleTrash(run)
        } catch (e: HttpError) {
            // Auth died mid-pass: halt without enqueueing further ops. A retry
            // with stale credentials can only loop forever.
            if (e.status == 401 || e.status == 403) return SyncOutcome.AuthFailed(e.status)
            throw e
        }
        return SyncOutcome.Completed(
            pulled = run.tally.pulled,
            pushed = run.tally.pushed,
            merged = run.tally.merged,
            forked = run.tally.forked,
            trashed = run.tally.trashed,
            resurrected = run.tally.resurrected,
            nothing = run.tally.nothing,
            expired = run.tally.expired,
        )
    }

    // ---- planning helpers ------------------------------------------------------

    private fun localStateOf(localNote: LocalNote?): NoteState =
        localNote?.let { NoteState(body = it.wholeFileText, trashed = it.trashed) }
            ?: NoteState(body = null, trashed = false)

    /** Depth:1 list of the live vault root, fetched whole, grouped by frontmatter id. */
    private fun fetchRemoteClaims(run: Run): Map<String, List<Claim>> {
        val entries = remote.list(VAULT_ROOT)
            .filter { entry ->
                val name = entry.fileName
                name.endsWith(MD_SUFFIX) &&
                    !name.startsWith(".") &&
                    !name.startsWith("$ATTACHMENTS_DIR")
            }
            .sortedBy { it.fileName }
        for (entry in entries) run.remoteNames += entry.fileName

        val byId = LinkedHashMap<String, MutableList<Claim>>()
        for (entry in entries) {
            val text = remote.get(entry.fileName)
            if (text == null) {
                flag("remote file vanished between listing and read: ${entry.fileName}")
                continue
            }
            val id = Frontmatter.parse(text).id?.takeIf { Ulid.isValid(it) }
            if (id == null) {
                // §12 ruling: identity assignment belongs to Setup's import step.
                flag("remote file without a usable id, left unsynced: ${entry.fileName}")
                continue
            }
            byId.getOrPut(id) { ArrayList() }.add(Claim(entry.fileName, entry.etag, text))
        }
        return byId
    }

    /** True when the Trash verdict would remove a LIVE copy from some live vault. */
    private fun trashRemovesLiveCopy(plan: Plan): Boolean = when {
        !plan.localState.present -> false // T0 confirmation corner: nothing lives anywhere
        plan.localState.trashed -> plan.remoteState.present // row 7: retires the far side
        else -> true // row 9: trashes the live local mirror
    }

    // ---- application dispatcher --------------------------------------------------

    /** One turn of the application loop: done for this note, or go again re-planned. */
    private sealed interface Step {
        data object Done : Step

        data class Again(val plan: Plan, val rounds: Int, val refusal: String?) : Step
    }

    private fun process(first: Plan, run: Run) {
        var plan = first
        var rounds = 0
        var lastRefusalReason: String? = null

        while (true) {
            when (plan.verdict) {
                Verdict.Pull -> {
                    pull(plan, run)
                    return
                }

                Verdict.Push -> {
                    // §10: the body's attachments land first or the body waits.
                    if (!ensureAttachmentsBeforeBody(plan.id, requireNotNull(plan.localText))) return
                    when (val result = pushWrite(plan)) {
                        is PutResult.WRITTEN -> {
                            book.recordBase(plan.id, requireNotNull(plan.localText), result.etag)
                            run.tally.pushed++
                            applied(plan.id, Verdict.Push, "pushed", ok = true)
                            return
                        }
                        PutResult.PRECONDITION_FAILED -> when (
                            val step = onPreconditionFailed(plan, rounds + 1, lastRefusalReason, run)
                        ) {
                            Step.Done -> return
                            is Step.Again -> {
                                plan = step.plan
                                rounds = step.rounds
                                lastRefusalReason = step.refusal
                            }
                        }
                        PutResult.FAILED -> {
                            book.enqueueOp(plan.id, OP_PUT, requireNotNull(plan.localText))
                            applied(plan.id, Verdict.Push, "push failed offline; queued to outbox", ok = false)
                            return
                        }
                    }
                }

                Verdict.Merge -> {
                    val base = checkNotNull(plan.base) { "Merge verdict requires a base snapshot" }
                    val localText = requireNotNull(plan.localText)
                    val remoteText = requireNotNull(plan.primary).text
                    when (val outcome = MergeEngine.merge(base.body, localText, remoteText)) {
                        is MergeEngine.MergeOutcome.Merged -> {
                            // §10: the merged text may carry references from either
                            // side; both must be on the server before this body.
                            if (!ensureAttachmentsBeforeBody(plan.id, outcome.wholeFileText)) return
                            when (
                                val result = putConditional(plan.workPath, outcome.wholeFileText, base)
                            ) {
                                is PutResult.WRITTEN -> {
                                    book.recordBase(plan.id, outcome.wholeFileText, result.etag)
                                    run.tally.merged++
                                    applied(plan.id, Verdict.Merge, "merged cleanly and pushed", ok = true)
                                    return
                                }
                                PutResult.PRECONDITION_FAILED ->
                                    // Re-plan re-decides from the mirror's real bytes; the merged
                                    // draft only mattered had the write landed.
                                    when (
                                        val step = onPreconditionFailed(plan, rounds + 1, null, run)
                                    ) {
                                        Step.Done -> return
                                        is Step.Again -> {
                                            plan = step.plan
                                            rounds = step.rounds
                                            lastRefusalReason = step.refusal
                                        }
                                    }
                                PutResult.FAILED -> {
                                    book.enqueueOp(plan.id, OP_PUT, outcome.wholeFileText)
                                    applied(
                                        plan.id, Verdict.Merge,
                                        "merged but push failed offline; queued to outbox", ok = false,
                                    )
                                    return
                                }
                            }
                        }
                        is MergeEngine.MergeOutcome.Fork -> {
                            lastRefusalReason = outcome.reason
                            fork(plan, cause = outcome.reason, run = run)
                            return
                        }
                    }
                }

                Verdict.Fork -> {
                    fork(plan, cause = FORK_ROW_11, run = run)
                    return
                }

                Verdict.Trash -> {
                    trash(plan, run)
                    return
                }

                Verdict.Resurrect -> {
                    if (plan.localState.trashed) {
                        resurrectFromRemote(plan, run) // row 8
                        return
                    }
                    // Row 10: the edit outranks the delete — recreate remotely, create-only.
                    // Ruling: a recreate is morally a push, so §10 ordering applies.
                    val text = requireNotNull(plan.localText)
                    if (!ensureAttachmentsBeforeBody(plan.id, text)) return
                    when (val result = remote.putIfAbsent(plan.workPath, text)) {
                        is PutResult.WRITTEN -> {
                            book.recordBase(plan.id, text, result.etag)
                            run.tally.resurrected++
                            applied(plan.id, Verdict.Resurrect, "edit outranks delete; re-pushed", ok = true)
                            return
                        }
                        PutResult.PRECONDITION_FAILED -> when (
                            val step = onPreconditionFailed(plan, rounds + 1, lastRefusalReason, run)
                        ) {
                            Step.Done -> return
                            is Step.Again -> {
                                plan = step.plan
                                rounds = step.rounds
                                lastRefusalReason = step.refusal
                            }
                        }
                        PutResult.FAILED -> {
                            book.enqueueOp(plan.id, OP_PUT, text)
                            applied(
                                plan.id, Verdict.Resurrect,
                                "resurrect push failed offline; queued to outbox", ok = false,
                            )
                            return
                        }
                    }
                }

                Verdict.Nothing -> {
                    adoptBaseCorner(plan)
                    run.tally.nothing++
                    applied(plan.id, Verdict.Nothing, "in sync", ok = true)
                    return
                }

                Verdict.Replan -> {
                    // decide() never emits Replan; row 12 lives in the push loops above.
                    applied(plan.id, Verdict.Replan, "unexpected Replan verdict; skipped", ok = false)
                    return
                }
            }
        }
    }

    /**
     * Row 12 shared tail: `If-Match` was rejected mid-push. Re-read the remote
     * (listing for the fresh ETag, GET for bytes), rebuild the triple, and
     * re-decide — up to [SyncPolicy]'s three-round bound, then fork.
     */
    private fun onPreconditionFailed(
        plan: Plan,
        nextRounds: Int,
        lastRefusalReason: String?,
        run: Run,
    ): Step = when (SyncPolicy.decidePushRejection(nextRounds)) {
        Verdict.Replan -> {
            val rebuilt = replan(plan)
            if (rebuilt == null) Step.Done else Step.Again(rebuilt, nextRounds, lastRefusalReason)
        }
        else -> {
            fork(plan, cause = lastRefusalReason ?: FORK_REPLAN_EXHAUSTED, run = run)
            Step.Done
        }
    }

    /** Row 12 re-read: fresh ETag from a re-listing, fresh bytes from a GET. */
    private fun replan(plan: Plan): Plan? {
        val preferredPath = plan.primary?.path
        val entries = try {
            remote.list(VAULT_ROOT)
        } catch (_: Exception) {
            flag("row 12: could not re-list during replan; ${plan.id} left for next sync")
            return null
        }

        fun claimFor(entry: RemoteEntry): Claim? {
            val text = remote.get(entry.fileName) ?: return null
            val parsed = Frontmatter.parse(text).id
            return if (parsed == plan.id) Claim(entry.fileName, entry.etag, text) else null
        }

        val claim = entries.firstOrNull { it.fileName == preferredPath }?.let(::claimFor)
            ?: entries.asSequence()
                .filter { it.fileName != preferredPath }
                .firstNotNullOfOrNull(::claimFor)

        if (claim == null) {
            flag("row 12: remote copy of ${plan.id} gone mid-replan; left for next sync")
            return null
        }
        return Plan(
            id = plan.id,
            base = book.baseOf(plan.id),
            localNote = plan.localNote,
            remoteState = NoteState(body = claim.text, trashed = false),
            claims = listOf(claim),
            verdict = SyncPolicy.decide(book.baseOf(plan.id), plan.localState, NoteState(claim.text, false)),
        )
    }

    // ---- verdict flows -----------------------------------------------------------

    /**
     * Row 7 precondition (M2): the trash collections must exist before any
     * MOVE targets them. Idempotent — already-exists counts as success — and
     * best-effort: a transport failure here just means the moves below fail
     * and are retried next sync.
     */
    private fun ensureTrashSkeleton() {
        remote.mkcol(TRASH_DIR.substringBefore('/'))
        remote.mkcol(TRASH_DIR)
    }

    // ---- §10 attachment upload ordering ---------------------------------------

    /**
     * The gate in front of every body-writing remote call (see the class
     * KDoc). Uploads every not-yet-remote attachment referenced by
     * [wholeFileText]; returns true only when the body push may proceed.
     * Unknown hash prefixes (no store row) and already-known rows pass
     * through untouched — ordering exists for OUR pipeline's pending uploads.
     */
    private fun ensureAttachmentsBeforeBody(noteId: String, wholeFileText: String): Boolean {
        val store = attachments ?: return true
        var ready = true
        for (prefix in AttachmentRefs.hashes(listOf(wholeFileText)).sorted()) {
            val row = store.rowByPrefix(prefix) ?: continue
            if (row.remoteKnown) continue
            val bytes = store.localBytes(prefix)
            if (bytes == null) {
                deferBodyPush(noteId, row.hash, prefix, "no local copy to upload")
                ready = false
                continue
            }
            when (remote.putFile("$ATTACHMENTS_DIR$prefix.${row.ext}", bytes)) {
                is PutResult.WRITTEN -> store.markRemoteKnown(row.hash)
                PutResult.PRECONDITION_FAILED -> {
                    deferBodyPush(noteId, row.hash, prefix, "server refused the write")
                    ready = false
                }
                PutResult.FAILED -> {
                    deferBodyPush(noteId, row.hash, prefix, "upload failed offline")
                    ready = false
                }
            }
        }
        return ready
    }

    /**
     * Deferral half of the law: queue an idempotent outbox marker (deduped —
     * re-deferrals never multiply ops) and speak the reason. The note itself
     * is left base-dirty, so the next pass naturally retries this attachment
     * before its body.
     */
    private fun deferBodyPush(noteId: String, fullHash: String, prefix16: String, why: String) {
        val queued = book.pendingOps().none {
            it.noteId == noteId && it.op == OP_ATTACH && it.payload == fullHash
        }
        if (queued) book.enqueueOp(noteId, OP_ATTACH, fullHash)
        book.log(
            SyncLogEntry(
                noteId = noteId,
                verdict = "Attach",
                reason = "attachment $prefix16 not uploaded — note push deferred ($why)",
                ok = false,
            ),
        )
    }

    private fun pull(plan: Plan, run: Run) {
        val claim = plan.primary ?: run {
            flag("${plan.id}: Pull verdict without a remote claim; skipped")
            return
        }
        local.write(plan.id, claim.text)
        book.recordBase(plan.id, claim.text, claim.etag)
        run.tally.pulled++
        applied(plan.id, Verdict.Pull, "pulled ${claim.path}", ok = true)
    }

    private fun trash(plan: Plan, run: Run) {
        when {
            // Corner T0: no live copy anywhere — the live vault is already correct.
            !plan.localState.present -> {
                run.tally.nothing++
                applied(plan.id, Verdict.Trash, "trash confirmed (remote copy already rests in trash)", ok = true)
            }
            // Row 7: the local user deleted; retire the far side's live copy. Base is
            // kept deliberately — trash is reversible (§6). The move carries
            // `Overwrite: F` so a same-named copy already in trash can never be
            // clobbered; on that precondition failure the name gets a `_1`-style
            // suffix and the move is retried (M2).
            plan.localState.trashed -> {
                val livePath = plan.primary?.path
                if (livePath == null) {
                    flag("${plan.id}: Trash verdict without a remote live path; skipped")
                    return
                }
                val stem = livePath.substringAfterLast('/').removeSuffix(MD_SUFFIX)
                var moved = false
                var target = "$TRASH_DIR/$stem$MD_SUFFIX"
                var attempt = 0
                while (!moved && attempt <= TRASH_MOVE_ATTEMPTS) {
                    moved = remote.move(livePath, target, overwrite = false)
                    if (!moved) {
                        attempt++
                        target = "$TRASH_DIR/${stem}_$attempt$MD_SUFFIX"
                    }
                }
                if (moved) run.tally.trashed++
                applied(
                    plan.id, Verdict.Trash,
                    if (moved) "remote copy moved to $target" else "remote MOVE rejected; retried next sync",
                    ok = moved,
                )
            }
            // Row 9: the far side deleted the note; move the local copy to trash — never unlink (D9).
            else -> {
                local.trash(plan.id)
                run.tally.trashed++
                applied(plan.id, Verdict.Trash, "remote gone; local copy moved to trash", ok = true)
            }
        }
    }

    /**
     * Row 8: the remote edit outranks the delete — but §15 forbids losing the
     * deleting side's text. The trashed copy is FIRST re-stamped as its own
     * note (fresh ULID, `conflictOf:` + `conflictAt:`), surviving in trash;
     * only then does the restored note take remote's bytes under the original
     * id. Both texts exist after the pass.
     */
    private fun resurrectFromRemote(plan: Plan, run: Run) {
        val claim = plan.primary ?: run {
            flag("${plan.id}: Resurrect verdict without a remote claim; skipped")
            return
        }
        val trashedText = plan.localNote?.wholeFileText
        if (plan.localState.trashed && trashedText != null) {
            val forkTrashId = Ulid.generateAt(clock().toEpochMilli())
            val now = clock().toString()
            local.forkTrashedCopy(plan.id, forkTrashId) { text ->
                Frontmatter.parse(text).rewritten {
                    id = forkTrashId
                    conflictOf = plan.id
                    conflictAt = now
                }
            }
            run.tally.forked++
            book.log(
                SyncLogEntry(
                    noteId = plan.id,
                    verdict = "Resurrect",
                    reason = "row 8: trashed copy re-stamped as $forkTrashId (conflictOf ${plan.id}) before restore",
                    ok = true,
                ),
            )
        }
        local.write(plan.id, claim.text)
        book.recordBase(plan.id, claim.text, claim.etag)
        run.tally.resurrected++
        applied(plan.id, Verdict.Resurrect, "remote edit outranks delete; restored", ok = true)
    }

    /** README corner: identical untracked copies on both sides adopt a base. */
    private fun adoptBaseCorner(plan: Plan) {
        val base = plan.base
        if (base == null &&
            plan.localState.present &&
            plan.remoteState.present &&
            plan.localState.body == plan.remoteState.body
        ) {
            book.recordBase(plan.id, requireNotNull(plan.localText), plan.primary?.etag)
            applied(plan.id, Verdict.Nothing, "identical untracked copies; adopted as base", ok = true)
        }
    }

    // ---- forks ---------------------------------------------------------------------

    /**
     * Creates the second visible note (§7): fresh ULID, `conflictOf:` +
     * `conflictAt:` stamped into the carried text's frontmatter, written to the
     * mirror AND uploaded create-only. The original's identity is untouched.
     * Advances the original's base to the fork-carried side (termination
     * ruling in the class KDoc).
     */
    private fun fork(plan: Plan, cause: String, run: Run) {
        val origId = plan.id
        val localText = plan.localText
        val remoteText = plan.primary?.text

        val forkSource = buildForkText(plan, cause)
        if (forkSource == null || localText == null || remoteText == null) {
            flag("$origId: Fork verdict missing a side to carry; left for next sync")
            return
        }

        val namer = newNamer { candidate ->
            candidate in run.remoteNames || local.freeName(candidate) != candidate
        }
        var forkPath = namer.forkName(plan.workPath)

        val freshId = Ulid.generateAt(clock().toEpochMilli())
        val forkWhole = Frontmatter.parse(forkSource).rewritten {
            id = freshId
            conflictOf = origId
            conflictAt = clock().toString()
        }

        local.write(freshId, forkWhole)

        // §10: the fork's own attachment references land before its body. A
        // blocked upload leaves the fork local-only (text is never lost) with
        // no base recorded — the next sync re-enters §6 as a plain row-2 push
        // once the attachments have landed.
        val attachmentsReady = ensureAttachmentsBeforeBody(origId, forkWhole)

        var uploaded: PutResult.WRITTEN? = null
        if (attachmentsReady) {
            var attempt = 0
            while (uploaded == null && attempt < FORK_NAME_ATTEMPTS) {
                attempt++
                when (val outcome = remote.putIfAbsent(forkPath, forkWhole)) {
                    is PutResult.WRITTEN -> {
                        uploaded = outcome
                        run.remoteNames += forkPath.substringAfterLast('/')
                    }
                    PutResult.PRECONDITION_FAILED -> forkPath = namer.forkName(plan.workPath)
                    PutResult.FAILED -> Unit
                }
            }
        }
        if (uploaded == null) {
            // No base recorded for the fork — next sync sees local-live/no-base and
            // re-enters §6 as a plain row-2 push. Never a phantom trash.
            applied(
                origId, Verdict.Fork,
                if (attachmentsReady) "fork ($cause) kept locally; upload failed offline"
                else "fork ($cause) kept locally; attachment upload deferred",
                ok = false,
            )
        }

        run.tally.forked++
        uploaded?.let { written ->
            book.recordBase(freshId, forkWhole, etag = written.etag)
            applied(origId, Verdict.Fork, "fork ($cause) created as $forkPath", ok = true)
        }

        // Termination ruling: base(original) := the side the fork carries. Next sync
        // reads local-dirty/remote-clean → row 4 push under If-Match — guarded, never blind.
        book.recordBase(origId, remoteText, plan.primary?.etag)
        book.log(
            SyncLogEntry(
                noteId = origId,
                verdict = "Fork",
                reason = "base advanced to fork-carried side; original converges via §6",
                ok = true,
            ),
        )
    }

    /**
     * The fork-text ruling (class KDoc): prose refusals carry diff3 markers over
     * the three bodies beneath the local frontmatter block; every other fork
     * carries the side NOT already in the mirror (the remote text here).
     */
    private fun buildForkText(plan: Plan, cause: String): String? {
        val localText = plan.localText ?: plan.primary?.text ?: return null
        val remoteText = plan.primary?.text ?: return null
        if (cause != PROSE_CONFLICT) return remoteText

        val baseBody = plan.base?.body?.let { Frontmatter.parse(it).bodyText } ?: ""
        val marked = Diff3.mergeWithMarkers(
            base = baseBody.lines(),
            local = Frontmatter.parse(localText).bodyText.lines(),
            remote = Frontmatter.parse(remoteText).bodyText.lines(),
            oursLabel = deviceLabel(),
            theirsLabel = THEIRS_LABEL,
        )
        val fmDoc = Frontmatter.parse(localText)
        val fmRegion =
            if (fmDoc.hasFrontmatter) fmDoc.raw().substring(0, fmDoc.raw().length - fmDoc.bodyText.length) else ""
        return fmRegion + marked.joinToString("\n") + "\n"
    }

    /** Row 11 corner: extra remote files claiming one id are re-identified in place. */
    private fun reidentifyDuplicateClaims(plan: Plan, run: Run) {
        for (extra in plan.claims.drop(1)) {
            val source = extra.text
            val freshId = Ulid.generateAt(clock().toEpochMilli())
            val restamped = Frontmatter.parse(source).rewritten {
                id = freshId
                conflictOf = plan.id
                conflictAt = clock().toString()
            }
            when (val written = remote.put(extra.path, restamped, ifMatch = extra.etag)) {
                is PutResult.WRITTEN -> {
                    local.write(freshId, restamped)
                    book.recordBase(freshId, restamped, written.etag)
                    run.remoteNames += extra.path.substringAfterLast('/')
                    run.tally.forked++
                    book.log(
                        SyncLogEntry(
                            noteId = plan.id,
                            verdict = "Fork",
                            reason = "duplicate id claimant ${extra.path} re-identified in place",
                            ok = true,
                        ),
                    )
                }
                else -> book.log(
                    SyncLogEntry(
                        noteId = plan.id,
                        verdict = "Fork",
                        reason = "duplicate id claimant ${extra.path} left untouched; retried next sync",
                        ok = false,
                    ),
                )
            }
        }
    }

    // ---- writes --------------------------------------------------------------------

    /** Row 2 uses create-only; rows 4+ use `If-Match` against the recorded base. */
    private fun pushWrite(plan: Plan): PutResult {
        val base = plan.base
        val text = requireNotNull(plan.localText)
        return if (base == null) {
            remote.putIfAbsent(plan.workPath, text)
        } else {
            remote.put(plan.workPath, text, base.etag)
        }
    }

    private fun putConditional(path: String, text: String, base: BaseSnapshot): PutResult =
        remote.put(path, text, base.etag)

    // ---- outbox ----------------------------------------------------------------------

    /**
     * Ops replay by re-deciding, never blind-retrying: after the pass, a queued
     * 'put' whose payload IS the last-agreed body has demonstrably landed and
     * drains; anything else is marked attempted and left queued. A queued
     * 'attach' op (§10 deferral marker) drains the moment its hash reads
     * `remoteKnown=true`; a row that no longer exists at all is marked
     * attempted so the zombie cap can retire it — an attachment that is gone
     * from the store can never upload.
     */
    private fun reconcileOutbox(run: Run) {
        for (op in book.pendingOps()) {
            if (op.noteId !in run.touchedIds) continue
            when (op.op) {
                OP_PUT -> {
                    val satisfied = book.baseOf(op.noteId)?.body == op.payload
                    if (satisfied) {
                        book.markOpDone(op.id)
                        book.log(
                            SyncLogEntry(
                                op.noteId, "Outbox",
                                "queued put matches the last-agreed bytes; drained", ok = true,
                            ),
                        )
                    } else {
                        book.markOpFailed(op.id, "superseded: note re-entered the sync table this pass")
                    }
                }
                OP_ATTACH -> {
                    val known = attachments?.rowByPrefix(op.payload.take(HEX_PREFIX))?.remoteKnown
                    when {
                        known == true -> {
                            book.markOpDone(op.id)
                            book.log(
                                SyncLogEntry(
                                    op.noteId, "Outbox",
                                    "queued attachment upload confirmed; drained", ok = true,
                                ),
                            )
                        }
                        known == null && attachments != null ->
                            book.markOpFailed(op.id, "attachment row gone; can never upload")
                        // Still pending (or no ordering seam): leave untouched — a
                        // deferral is a normal state, not a failure to count.
                    }
                }
            }
        }
    }

    // ---- D9 expiry sweep ---------------------------------------------------------

    /**
     * Expired trash leaves BOTH sides (D9): every `.md` resting in the remote
     * trash whose own bytes carry a `trashedAt:` older than
     * [TRASH_RETENTION_MS] is unlinked (`RemoteFiles.delete` — its only lawful
     * caller), then [LocalFiles.purgeExpiredTrash] clears the mirror side in
     * one pass. BELT-AND-BRACES: a copy without a parsable `trashedAt:` is
     * never deleted anywhere — it is skipped and flagged instead. Per-entry
     * transport trouble is logged and skipped (failure direction: preserve);
     * an auth refusal propagates to the pass's AuthFailed handling.
     */
    private fun expireStaleTrash(run: Run) {
        val now = clock().toEpochMilli()
        val entries = try {
            remote.list(TRASH_DIR)
        } catch (e: HttpError) {
            if (e.status == 401 || e.status == 403) throw e
            flag("expiry: could not list $TRASH_DIR; remote sweep skipped this pass")
            emptyList()
        } catch (_: Exception) {
            flag("expiry: could not list $TRASH_DIR; remote sweep skipped this pass")
            emptyList()
        }
        for (entry in entries.filter { it.fileName.endsWith(MD_SUFFIX) }) {
            val path = "$TRASH_DIR/${entry.fileName}"
            try {
                val text = remote.get(path)
                if (text == null) continue // vanished between listing and read
                val stamp = Frontmatter.parse(text).trashedAt?.let { stamp ->
                    runCatching { Instant.parse(stamp) }.getOrNull()
                }
                if (stamp == null) {
                    // NEVER delete without the stamp (D9 belt-and-braces).
                    book.log(
                        SyncLogEntry(
                            noteId = null,
                            verdict = "Expire",
                            reason = "no parsable trashedAt on $path; left untouched",
                            ok = false,
                        ),
                    )
                    continue
                }
                if (now - stamp.toEpochMilli() <= TRASH_RETENTION_MS) continue // inside retention
                if (remote.delete(path)) run.tally.expired++
                book.log(
                    SyncLogEntry(
                        noteId = null,
                        verdict = "Expire",
                        reason = "trash past ${TRASH_RETENTION_MS / DAY_MS} days expired; $path deleted",
                        ok = true,
                    ),
                )
            } catch (e: HttpError) {
                if (e.status == 401 || e.status == 403) throw e
                flag("expiry: $path survived this pass: HTTP ${e.status}")
            } catch (_: Exception) {
                flag("expiry: $path survived this pass (transport)")
            }
        }
        // Local half: one purge pass over the mirror's trash, same retention law.
        try {
            run.tally.expired += local.purgeExpiredTrash(TRASH_RETENTION_MS, now)
        } catch (_: Exception) {
            flag("expiry: local trash purge failed; retried next sync")
        }
    }

    // ---- plumbing ----------------------------------------------------------------------

    private fun applied(noteId: String, verdict: Verdict, reason: String, ok: Boolean) {
        book.log(SyncLogEntry(noteId, verdict.name, reason, ok))
    }

    private fun flag(reason: String) {
        book.log(SyncLogEntry(null, "Fetch", reason, ok = false))
    }

    private fun deviceLabel(): String =
        com.piercingxx.xxnote.core.Slug.of(deviceName).ifEmpty { OURS_FALLBACK_LABEL }

    companion object {
        const val DEFAULT_TRASH_SAFETY_THRESHOLD = 0.25

        internal const val VAULT_ROOT = ""
        internal const val TRASH_DIR = ".xxnote/trash"
        internal const val MD_SUFFIX = ".md"
        internal const val ATTACHMENTS_DIR = "attachments/"
        internal const val OP_PUT = "put"
        internal const val OP_ATTACH = "attach"

        /** §10: attachment filenames carry this many hex chars of the SHA-256. */
        private const val HEX_PREFIX = 16

        internal const val PROSE_CONFLICT = "prose conflict"
        internal const val FORK_ROW_11 = "two files claim one id"
        internal const val FORK_REPLAN_EXHAUSTED = "replan exhausted"
        internal const val THEIRS_LABEL = "remote"
        internal const val OURS_FALLBACK_LABEL = "this-device"

        /** D9: trash rests this long, then the expiry sweep unlinks it. */
        internal const val TRASH_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private const val FORK_NAME_ATTEMPTS = 8
        private const val TRASH_MOVE_ATTEMPTS = 8
    }
}
