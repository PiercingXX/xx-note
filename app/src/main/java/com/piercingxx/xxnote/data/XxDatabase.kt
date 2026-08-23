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
    }
}
