package ru.fornax.fornaxcitadel.core.storage

import java.util.concurrent.CompletableFuture

// Отвечает за хранение цитаделей. Возвращает live представление, изменение которой отслеживается через dirty
interface CitadelStorage {
    fun getIfChunkLoaded(location: WXZCord): ChunkCord?
    fun loadChunk(location: WXZCord): CompletableFuture<ChunkCord>
    fun unloadChunk(location: WXZCord): CompletableFuture<Void>
    fun saveAll(): CompletableFuture<Void>
}
