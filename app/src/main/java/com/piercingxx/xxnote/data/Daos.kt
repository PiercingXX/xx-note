package com.piercingxx.xxnote.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface NoteDao {

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("SELECT * FROM note WHERE id = :id")
    suspend fun byId(id: String): NoteEntity?

    @Query("SELECT * FROM note WHERE trashedAt IS NULL ORDER BY pinned DESC, updated DESC")
    suspend fun listLive(): List<NoteEntity>

    @Query("SELECT * FROM note WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
    suspend fun listTrashedRows(): List<NoteEntity>

    @Query("DELETE FROM note WHERE id = :id")
    suspend fun delete(id: String)

    /** FTS search over title + body, live notes only (D16: search is local, R4). */
    @Query(
        "SELECT note.* FROM note JOIN note_fts ON note.rowid = note_fts.rowid " +
            "WHERE note_fts MATCH :query AND note.trashedAt IS NULL " +
            "ORDER BY note.updated DESC",
    )
    suspend fun search(query: String): List<NoteEntity>

    /**
     * Live notes whose id appears as `conflictOf` on another live note —
     * the originals with unresolved forks (§7), i.e. the sync screen's
     * conflict list (R10). Read-only; WS9 only.
     */
    @Query(
        "SELECT * FROM note WHERE trashedAt IS NULL AND id IN " +
            "(SELECT conflictOf FROM note WHERE trashedAt IS NULL AND conflictOf IS NOT NULL) " +
            "ORDER BY updated DESC",
    )
    suspend fun conflictsList(): List<NoteEntity>
}

/** Base snapshots — the entire reason three-way merge is possible (D7). */
@Dao
interface BaseSnapshotDao {

    @Upsert
    suspend fun upsert(snapshot: BaseSnapshotEntity)

    @Query("SELECT * FROM base_snapshot WHERE id = :noteId")
    suspend fun byId(noteId: String): BaseSnapshotEntity?

    @Query("DELETE FROM base_snapshot WHERE id = :noteId")
    suspend fun delete(noteId: String)
}

@Dao
interface OutboxDao {

    @Insert
    suspend fun insert(op: OutboxEntity): Long

    /** Ops not yet confirmed applied, oldest first. */
    @Query("SELECT * FROM outbox ORDER BY queuedAt ASC, id ASC")
    suspend fun pending(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE id = :opId")
    suspend fun byId(opId: Long): OutboxEntity?

    @Query("DELETE FROM outbox WHERE id = :opId")
    suspend fun markDone(opId: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE id = :opId")
    suspend fun markOpFailed(opId: Long, error: String)
}

@Dao
interface SyncLogDao {

    companion object {
        /** Same shape and cap as XX-Phone's screen_log (§11). */
        const val LOG_CAP = 1000
    }

    @Insert
    suspend fun insert(entry: SyncLogEntity)

    @Query("SELECT * FROM sync_log ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<SyncLogEntity>

    /**
     * Entries at or after [since] (epoch millis), newest first — the weekly
     * tallies' trailing window (§12 item 4). Read-only; WS9 only.
     */
    @Query("SELECT * FROM sync_log WHERE at >= :since ORDER BY at DESC, id DESC")
    suspend fun logsSince(since: Long): List<SyncLogEntity>

    @Query(
        "DELETE FROM sync_log WHERE id NOT IN " +
            "(SELECT id FROM sync_log ORDER BY id DESC LIMIT ${SyncLogDao.LOG_CAP})",
    )
    suspend fun pruneToCap()
}

@Dao
interface LabelDao {

    @Upsert
    suspend fun upsert(label: LabelEntity)

    @Query("SELECT * FROM label ORDER BY sortIndex ASC, name ASC")
    suspend fun all(): List<LabelEntity>

    @Query("DELETE FROM label WHERE name = :name")
    suspend fun delete(name: String)
}

@Dao
interface NoteLabelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(link: NoteLabelEntity)

    @Query("SELECT name FROM note_label WHERE noteId = :noteId")
    suspend fun labelsFor(noteId: String): List<String>

    @Query("SELECT noteId FROM note_label WHERE name = :name")
    suspend fun notesFor(name: String): List<String>

    @Query("DELETE FROM note_label WHERE noteId = :noteId")
    suspend fun clearFor(noteId: String)

    @Query("DELETE FROM note_label WHERE name = :name")
    suspend fun removeLabel(name: String)
}

@Dao
interface AttachmentDao {

    @Upsert
    suspend fun upsert(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachment WHERE hash = :hash")
    suspend fun byHash(hash: String): AttachmentEntity?

    /**
     * WS10 (AttachmentStore): full enumeration for the orphan diff
     * (`orphanCount`/`sweepOrphans`) and LRU eviction (`evictToBudget`).
     * Sorting/filtering happens in Kotlin so the logic stays pure-JVM testable.
     */
    @Query("SELECT * FROM attachment")
    suspend fun all(): List<AttachmentEntity>

    /**
     * WS10 (AttachmentStore): row removal for [sweepOrphans] — the local file
     * is unlinked separately; the remote copy is never touched from here (§10:
     * a reference may live in an unpulled note).
     */
    @Query("DELETE FROM attachment WHERE hash = :hash")
    suspend fun delete(hash: String)
}

@Dao
interface SettingDao {

    @Query("SELECT value FROM setting WHERE key = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(setting: SettingEntity)

    @Query("DELETE FROM setting WHERE key = :key")
    suspend fun delete(key: String)
}

@Dao
interface CredentialDao {

    @Upsert
    suspend fun upsert(credential: CredentialEntity)

    @Query("SELECT * FROM credential WHERE id = 1")
    suspend fun get(): CredentialEntity?

    @Query("DELETE FROM credential WHERE id = 1")
    suspend fun clear()
}
