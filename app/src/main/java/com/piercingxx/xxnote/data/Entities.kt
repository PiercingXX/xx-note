package com.piercingxx.xxnote.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Design §11, transcribed verbatim into Room. `note.body` is WHOLE-FILE text —
 * the entire `.md` file including the YAML frontmatter block (the WHOLE-FILE
 * LAW pinned in sync/Ports.kt). The vault file is truth (D1); this row is a
 * cache rebuilt from it whenever they disagree.
 */
@Entity(tableName = "note")
data class NoteEntity(
    /** Frontmatter ULID identity, never the path (D3). */
    @PrimaryKey val id: String,
    val path: String,
    val title: String,
    val body: String,
    val created: Long,
    val updated: Long,
    val pinned: Boolean,
    val archived: Boolean,
    val color: String?,
    /** `note` | `checklist` (§8) — behavioral, not cosmetic. */
    val type: String,
    val trashedAt: Long?,
    val conflictOf: String?,
    val extraFrontmatter: String?,
)

/**
 * FTS over (title, body), external content of [NoteEntity]. "Id-linked" per
 * §11: matches resolve through `JOIN note ON note.rowid = note_fts.rowid`.
 */
@Fts4(contentEntity = NoteEntity::class)
@Entity(tableName = "note_fts")
data class NoteFtsEntity(
    val title: String,
    val body: String,
)

/**
 * The last-agreed snapshot for a note (D7) — neither side's current state.
 * [body] is WHOLE-FILE text; [frontmatter] is the raw frontmatter block region
 * (`""` when the file has none).
 */
@Entity(tableName = "base_snapshot")
data class BaseSnapshotEntity(
    @PrimaryKey val id: String,
    val body: String,
    val frontmatter: String,
    /** Null when ETags proved unusable in WS0 and §4.2's fallback applies. */
    val etag: String?,
    val remoteMtime: Long,
    val syncedAt: Long,
)

/** Durable op queue; survives process death and reboot (§11). */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: String,
    /** `'put'` | `'move'` | `'trash'` | `'delete'` | `'attach'`. */
    val op: String,
    val payload: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val queuedAt: Long,
)

@Entity(tableName = "label")
data class LabelEntity(
    @PrimaryKey val name: String,
    val sortIndex: Int,
)

/** Flat tag links; labels live in frontmatter too — this table is derived cache. */
@Entity(tableName = "note_label", primaryKeys = ["noteId", "name"])
data class NoteLabelEntity(
    val noteId: String,
    val name: String,
)

/** Content-addressed attachment metadata (D13). */
@Entity(tableName = "attachment")
data class AttachmentEntity(
    /** SHA-256 hex of the bytes. */
    @PrimaryKey val hash: String,
    val ext: String,
    val bytes: Long,
    val w: Int,
    val h: Int,
    val localPath: String?,
    val lastViewedAt: Long,
    val remoteKnown: Boolean,
)

/** The reason-string store behind R10; capped at [SyncLogDao.LOG_CAP] rows. */
@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val at: Long,
    val noteId: String?,
    val verdict: String,
    val reason: String,
    val ok: Boolean,
    val detail: String?,
)

@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * Singleton sync credential row (`id` is always 1). [sealedSecret] holds AES-GCM
 * ciphertext under a hardware-backed Keystore key (R9) — never plaintext.
 */
@Entity(tableName = "credential")
data class CredentialEntity(
    @PrimaryKey val id: Int = 1,
    val host: String,
    val basePath: String,
    val user: String,
    val sealedSecret: ByteArray,
    val keyAlias: String,
) {
    override fun equals(other: Any?): Boolean =
        other is CredentialEntity &&
            other.id == id &&
            other.host == host &&
            other.basePath == basePath &&
            other.user == user &&
            other.sealedSecret.contentEquals(sealedSecret) &&
            other.keyAlias == keyAlias

    override fun hashCode(): Int {
        var h = id
        h = 31 * h + host.hashCode()
        h = 31 * h + basePath.hashCode()
        h = 31 * h + user.hashCode()
        h = 31 * h + sealedSecret.contentHashCode()
        h = 31 * h + keyAlias.hashCode()
        return h
    }
}
