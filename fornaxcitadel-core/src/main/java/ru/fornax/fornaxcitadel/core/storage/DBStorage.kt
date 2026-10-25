package ru.fornax.fornaxcitadel.core.storage

import java.util.concurrent.CompletableFuture

interface DBStorage {
    /**
     * Loads the persistent snapshot for [location]. Implementations own any required
     * thread switching and report operational failures through the future.
     */
    fun loadChunk(location: WXZCord): CompletableFuture<ChunkCordSnapshot?>

    /**
     * Persists [snapshot]. The future completes only after the backend finishes the write
     * (for example, after a SQL transaction commits).
     */
    fun saveChunk(snapshot: ChunkCordSnapshot): CompletableFuture<Void>
}
