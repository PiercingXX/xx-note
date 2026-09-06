package com.piercingxx.xxnote.ui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.piercingxx.xxnote.data.AttachmentStore
import com.piercingxx.xxnote.data.VaultStore
import com.piercingxx.xxnote.data.XxDatabase
import com.piercingxx.xxnote.sync.SyncWorker
import com.piercingxx.xxnote.ui.setup.SetupLogic
import java.io.File
import java.time.Instant

/** Android Intent seam for share-to-note (P2). Mapping itself stays in [ShareMapping]. */
object ShareIntents {

    data class Request(
        val subject: String?,
        val text: String?,
        val extraTexts: List<String>,
        val streams: List<Uri>,
        val mimeType: String?,
    )

    fun parse(intent: Intent?): Request? {
        intent ?: return null
        val action = intent.action ?: return null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val extraTexts = extraTextList(intent)
        val streams = streamsOf(intent, action)
        return Request(
            subject = subject,
            text = text,
            extraTexts = extraTexts,
            streams = streams,
            mimeType = intent.type,
        )
    }

    suspend fun ingest(context: Context, request: Request): ShareIngest.Outcome {
        val app = context.applicationContext
        val db = XxDatabase.getInstance(app)
        val hasCredential = db.credentialDao().get() != null
        val localOnly = SetupLogic.isLocalOnlySentinel(db.settingDao().get(SetupLogic.KEY_LOCAL_ONLY))
        ShareIngest.gate(hasCredential, localOnly)?.let { return it }

        val files = ArrayList<ShareIngest.TextFile>(request.streams.size)
        for (uri in request.streams) {
            val mime = app.contentResolver.getType(uri) ?: request.mimeType
            if (!ShareMapping.isTextMime(mime)) {
                return ShareIngest.Outcome.Refused(ShareIngest.REFUSED_NOT_TEXT)
            }
            val bytes = try {
                app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            } ?: return ShareIngest.Outcome.Refused(ShareIngest.REFUSED_UNREADABLE)
            val name = ShareMapping.safeName(uri.lastPathSegment ?: "shared.txt")
            files += ShareIngest.TextFile(name = name, mime = mime, bytes = bytes)
        }

        val mapped = ShareMapping.map(
            subject = request.subject,
            extraText = request.text,
            extraTexts = request.extraTexts,
            attachmentNames = files.map { it.name },
        )
        val store = VaultStore(app)
        val attachments = if (files.isEmpty()) {
            null
        } else {
            AttachmentStore(
                vaultRoot = File(app.filesDir, VaultStore.MIRROR_DIR),
                dao = db.attachmentDao(),
            )
        }
        return ShareIngest.create(
            store = store,
            attachments = attachments,
            title = mapped.title,
            body = mapped.body,
            files = files,
            nowIso = Instant.now().toString(),
            enqueueSync = {
                try {
                    SyncWorker.enqueueExpedited(app)
                } catch (_: Exception) {
                    // Capture's rule: the note is already on disk.
                }
            },
        )
    }

    private fun extraTextList(intent: Intent): List<String> {
        val raw = runCatching { intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT) }.getOrNull()
            ?: return emptyList()
        return raw.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
    }

    private fun streamsOf(intent: Intent, action: String): List<Uri> = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                .orEmpty()
        else -> emptyList()
    }
}
