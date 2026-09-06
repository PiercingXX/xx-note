package com.piercingxx.xxnote.ui.share

import com.piercingxx.xxnote.core.Ulid
import com.piercingxx.xxnote.data.AttachmentStore
import com.piercingxx.xxnote.data.VaultStore
import com.piercingxx.xxnote.ui.setup.SetupLogic

/**
 * Share-to-note write path (P2). Same vault write + expedited sync as capture:
 * the new `.md` is dirty (no base snapshot) so the next pass pushes it. No
 * timestamp winner-picking. Setup unfinished with no local-only sentinel
 * refuses to write into a missing vault.
 */
object ShareIngest {

    sealed class Outcome {
        data class Created(val noteId: String) : Outcome()
        data object NeedSetup : Outcome()
        data class Refused(val reason: String) : Outcome()
    }

    const val NEED_SETUP_WORDS =
        "Set up XX-Note first — there is no vault to write into yet."
    const val REFUSED_NOT_TEXT = "This share is not text, so it was not saved."
    const val REFUSED_UNREADABLE = "The shared file could not be read, so it was not saved."

    data class TextFile(val name: String, val mime: String?, val bytes: ByteArray)

    /** Null when a vault exists (credential or local-only sentinel). */
    fun gate(hasCredential: Boolean, localOnly: Boolean): Outcome? =
        if (SetupLogic.startOnSetup(hasCredential, localOnly)) Outcome.NeedSetup else null

    suspend fun create(
        store: VaultStore,
        attachments: AttachmentStore?,
        title: String,
        body: String,
        files: List<TextFile>,
        nowIso: String,
        id: String = Ulid.generate(),
        enqueueSync: () -> Unit,
    ): Outcome {
        for (file in files) {
            if (!ShareMapping.isTextMime(file.mime)) return Outcome.Refused(REFUSED_NOT_TEXT)
        }
        val links = ArrayList<String>(files.size)
        if (files.isNotEmpty()) {
            val attStore = attachments ?: return Outcome.Refused(REFUSED_NOT_TEXT)
            for (file in files) {
                val ext = ShareMapping.extFor(file.mime, file.name)
                val result = attStore.insertRaw(file.bytes, ext)
                links += "[${ShareMapping.safeName(file.name)}](${result.relativePath})"
            }
        }
        val whole = ShareMapping.noteText(
            id = id,
            title = title,
            body = ShareMapping.appendLinks(body, links),
            nowIso = nowIso,
        )
        store.write(id, whole)
        try {
            enqueueSync()
        } catch (_: Exception) {
            // Same as capture: the note exists; sync absence never uncreates it.
        }
        return Outcome.Created(id)
    }
}
