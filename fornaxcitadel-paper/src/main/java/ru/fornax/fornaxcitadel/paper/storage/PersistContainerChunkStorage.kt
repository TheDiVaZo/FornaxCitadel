package ru.fornax.fornaxcitadel.paper.storage

import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.DBStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Stores opaque chunk data in a Bukkit chunk PDC.
 * Bukkit access is scheduled on the main thread and uses only explicitly registered live chunks.
 * A successful write updates the chunk's PDC; Minecraft's chunk saving provides disk persistence.
 */
class PersistContainerChunkStorage(
    plugin: Plugin,
    private val scheduler: Scheduler,
    private val dataKey: NamespacedKey = NamespacedKey(plugin, DEFAULT_KEY)
) : DBStorage {
    private val server = plugin.server
    private val chunks = HashMap<WXZCord, RegisteredChunk>()

    fun registerChunk(chunk: Chunk) {
        checkMainThread()
        val location = locationOf(chunk)
        val previous = chunks[location]
        check(previous == null || previous.chunk.world === chunk.world) {
            "A different Bukkit world instance is already registered at $location"
        }
        if (previous == null) {
            chunks[location] = RegisteredChunk(chunk)
        } else {
            // Paper returns new Chunk wrappers from events and getLoadedChunks().
            // Keep the loaded-coordinate binding and its pending-save marker.
            previous.chunk = chunk
        }
    }

    fun unregisterChunk(chunk: Chunk) {
        checkMainThread()
        val location = locationOf(chunk)
        if (chunks[location]?.chunk?.world === chunk.world) chunks.remove(location)
    }

    /** Conservatively keeps our PDC writes when another caller requested an unsaved unload. */
    fun hasPendingChunkSave(chunk: Chunk): Boolean {
        checkMainThread()
        val registered = chunks[locationOf(chunk)]
        return registered != null && registered.chunk.world === chunk.world && registered.written
    }

    fun clearRegisteredChunks() {
        checkMainThread()
        chunks.clear()
    }

    fun registeredChunks(worldId: UUID): List<Chunk> {
        checkMainThread()
        return chunks.values.mapNotNull { registered ->
            registered.chunk.takeIf { it.world.uid == worldId }
        }
    }

    override fun loadChunk(
        location: WXZCord
    ): CompletableFuture<ChunkCordSnapshot?> {
        return try {
            scheduler.schedule<ByteArray?>(location) {
                registeredChunk(location).chunk
                    .persistentDataContainer
                    .get(dataKey, PersistentDataType.BYTE_ARRAY)
                    ?.copyOf()
            }.thenApply { data ->
                data?.let { decodeSnapshot(location, it) }
            }
        } catch (exception: Exception) {
            CompletableFuture.failedFuture(exception)
        }
    }

    override fun saveChunk(
        snapshot: ChunkCordSnapshot
    ): CompletableFuture<Void> {
        return try {
            val data = snapshot.toByteArray()
            scheduler.schedule(snapshot.location) {
                val registered = registeredChunk(snapshot.location)
                registered.chunk.persistentDataContainer
                    .set(dataKey, PersistentDataType.BYTE_ARRAY, data)
                registered.written = true
            }.thenAccept { }
        } catch (exception: Exception) {
            CompletableFuture.failedFuture(exception)
        }
    }

    private fun decodeSnapshot(
        location: WXZCord,
        data: ByteArray
    ): ChunkCordSnapshot {
        val snapshot = ChunkCordSnapshot.fromByteArray(data)
        if (snapshot.location != location) {
            throw IOException(
                "Stored chunk ${snapshot.location.world} " +
                    "[${snapshot.location.x}, ${snapshot.location.z}] " +
                    "does not match requested chunk ${location.world} " +
                    "[${location.x}, ${location.z}]"
            )
        }
        return snapshot
    }

    private fun registeredChunk(location: WXZCord): RegisteredChunk {
        checkMainThread()
        val registered = checkNotNull(chunks[location]) { "Bukkit chunk is not registered: $location" }
        // CraftChunk#getPersistentDataContainer can load its coordinates if the chunk expired.
        // The unload event is dispatched before Paper removes the chunk from the loaded set.
        check(registered.chunk.world.isChunkLoaded(location.x, location.z)) {
            "Bukkit chunk is no longer loaded: $location"
        }
        return registered
    }

    private fun checkMainThread() = check(server.isPrimaryThread) {
        "Chunk PDC access must run on the main thread"
    }

    private fun locationOf(chunk: Chunk) = WXZCord(chunk.world.uid, chunk.x, chunk.z)

    private class RegisteredChunk(var chunk: Chunk, var written: Boolean = false)

    private companion object {
        const val DEFAULT_KEY = "chunk_data"
    }
}
