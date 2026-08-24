package com.piercingxx.xxnote.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The cache-and-outbox database (D1, design §11) — never the source of truth.
 * Version 1 is the first schema; migrations arrive when the format evolves.
 */
@Database(
    entities = [
        NoteEntity::class,
        NoteFtsEntity::class,
        BaseSnapshotEntity::class,
        OutboxEntity::class,
        LabelEntity::class,
        NoteLabelEntity::class,
        AttachmentEntity::class,
        SyncLogEntity::class,
        SettingEntity::class,
        CredentialEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class XxDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun baseSnapshotDao(): BaseSnapshotDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun labelDao(): LabelDao
    abstract fun noteLabelDao(): NoteLabelDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun settingDao(): SettingDao
    abstract fun credentialDao(): CredentialDao

    companion object {
        const val NAME = "xx-note.db"

        fun builder(context: Context): RoomDatabase.Builder<XxDatabase> =
            Room.databaseBuilder(context.applicationContext, XxDatabase::class.java, NAME)

        /** Cached handle paired with the filesDir path it was built against. */
        @Volatile
        private var instance: Pair<String, XxDatabase>? = null

        /**
         * The process-wide database instance every production call site shares
         * (hardening #9): one connection pool per process, so a future
         * observable query invalidates correctly with no need for
         * `enableMultiInstanceInvalidation()` — there is only ever one
         * instance to invalidate.
         *
         * The holder is keyed by the application filesDir path rather than
         * held unconditionally so test environments stay isolated: Robolectric
         * statics survive across test methods while each method gets a fresh
         * temporary filesDir, and a stale handle bound to a deleted directory
         * would leak rows between tests. In production the path is constant,
         * so exactly one instance lives for the whole process.
         *
         * [builder] remains for tests that want an isolated throwaway database.
         */
        fun getInstance(context: Context): XxDatabase {
            val filesDir = context.applicationContext.filesDir.absolutePath
            instance?.takeIf { it.first == filesDir }?.let { return it.second }
            return synchronized(this) {
                instance?.takeIf { it.first == filesDir }?.second
                    ?: builder(context).build().also { instance = filesDir to it }
            }
        }
    }
}
