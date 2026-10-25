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
import java.util.concurrent.CompletableFuture

/**
 * Stores opaque chunk data in a Bukkit chunk PDC.
 * Bukkit access is scheduled on the thread that owns the chunk.
 */
class PersistContainerChunkStorage(
    plugin: Plugin,
    private val scheduler: Scheduler,
    private val dataKey: NamespacedKey = NamespacedKey(plugin, DEFAULT_KEY)
) : DBStorage {
    private val server = plugin.server

    override fun loadChunk(
        location: WXZCord
    ): CompletableFuture<ChunkCordSnapshot?> {
        return try {
            scheduler.schedule<ByteArray?>(location) {
                getBukkitChunk(location)
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
            scheduler.schedule(snapshot) {
                getBukkitChunk(snapshot)
                    .persistentDataContainer
                    .set(dataKey, PersistentDataType.BYTE_ARRAY, data)
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
        if (
            snapshot.world != location.world ||
            snapshot.x != location.x ||
            snapshot.z != location.z
        ) {
            throw IOException(
                "Stored chunk ${snapshot.world} [${snapshot.x}, ${snapshot.z}] " +
                    "does not match requested chunk ${location.world} " +
                    "[${location.x}, ${location.z}]"
            )
        }
        return snapshot
    }

    private fun getBukkitChunk(location: WXZCord): Chunk {
        val world = requireNotNull(server.getWorld(location.world)) {
            "World ${location.world} is not loaded"
        }
        return world.getChunkAt(location.x, location.z)
    }

    private companion object {
        const val DEFAULT_KEY = "chunk_data"
    }
}
