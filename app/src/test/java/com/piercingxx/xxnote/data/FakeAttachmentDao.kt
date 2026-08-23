package com.piercingxx.xxnote.data

/**
 * In-memory [AttachmentDao] for pure-JVM AttachmentStore tests — no Room, no
 * Robolectric. Insertion order is preserved so tests can also observe
 * enumeration order if they care to.
 */
class FakeAttachmentDao : AttachmentDao {

    val rows = LinkedHashMap<String, AttachmentEntity>()

    override suspend fun upsert(attachment: AttachmentEntity) {
        rows[attachment.hash] = attachment
    }

    override suspend fun byHash(hash: String): AttachmentEntity? = rows[hash]

    override suspend fun all(): List<AttachmentEntity> = rows.values.toList()

    override suspend fun delete(hash: String) {
        rows.remove(hash)
    }
}
