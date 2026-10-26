package ru.fornax.fornaxcitadel.core.storage

/**
 * Save completion may acknowledge a revision from a backend thread. This only synchronizes
 * dirty tracking; chunk contents must still be accessed on their owning thread.
 * Kept separate so a pending unload retains no live chunk or component references.
 */
internal class ChunkDirtyState {
    private var revision = 0L
    private var cleanRevision = 0L

    val isDirty: Boolean
        @Synchronized get() = revision != cleanRevision

    @Synchronized
    fun markDirty() {
        revision++
    }

    @Synchronized
    fun resetDirty() {
        cleanRevision = revision
    }

    @Synchronized
    fun captureDirtyRevision(): Long? {
        return revision.takeIf { it != cleanRevision }
    }

    @Synchronized
    fun resetDirty(savedRevision: Long) {
        if (revision == savedRevision) cleanRevision = revision
    }
}
