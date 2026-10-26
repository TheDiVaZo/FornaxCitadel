package ru.fornax.fornaxcitadel.core.storage

import ru.fornax.fornaxcitadel.core.util.Key
import java.util.concurrent.CompletableFuture

/**
 * Provides asynchronous access to live citadel chunks and coordinates their persistence lifecycle.
 */
interface CitadelStorage {
    fun getIfChunkLoaded(location: WXZCord): ChunkCord?
    fun loadChunk(location: WXZCord): CompletableFuture<ChunkCord>
    fun unloadChunk(location: WXZCord): CompletableFuture<Void>
    fun updateOpaque(chunk: ChunkCord): CompletableFuture<OpaqueUpdateResult>

    /**
     * Scans chunks loaded at the time of the call, excluding those with an accepted unload.
     * Each scan runs in the chunk's operation queue on its owner thread. Matching components
     * are captured when that scan executes; chunks without matches do not count as processed.
     */
    fun updateOpaque(key: Key): CompletableFuture<OpaqueBatchUpdateResult>
    fun saveAll(): CompletableFuture<Void>

    /**
     * Stops accepting new operations, snapshots every accepted live chunk, closes the backend,
     * and releases loaded chunks. Previously returned live chunks must not be mutated afterwards.
     */
    fun closeAndSaveAll(): CompletableFuture<Void>
}
