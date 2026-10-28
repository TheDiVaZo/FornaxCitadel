package ru.fornax.fornaxcitadel.paper.storage

import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.logging.Level

/** Uses a direct codec executor: chunk PDC operations finish before their Bukkit event returns. */
class PaperChunkListener(
    private val plugin: Plugin,
    private val manager: SimpleComponentManager,
    private val backend: PersistContainerChunkStorage
) : Listener {
    fun loadAlreadyLoadedChunks() {
        checkMainThread()
        for (world in plugin.server.worlds) {
            for (chunk in world.loadedChunks) load(chunk)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onLoad(event: ChunkLoadEvent) = load(event.chunk)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onUnload(event: ChunkUnloadEvent) {
        checkMainThread()
        val chunk = event.chunk
        if (!supported(chunk)) return
        try {
            requireCompleted(manager.unloadChunk(chunk.world.uid, chunk.x, chunk.z))
        } catch (failure: Exception) {
            // Core retains a failed unload snapshot and retries when the chunk is loaded again.
            report("save and unload", chunk, failure)
        } finally {
            if (backend.hasPendingChunkSave(chunk)) event.isSaveChunk = true
            backend.unregisterChunk(chunk)
        }
    }

    /**
     * WorldUnloadEvent does not expose unloadWorld's save flag. Flush our changed PDC before
     * allowing the world to disappear, then reconcile a possible later cancellation next tick.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) {
        checkMainThread()
        val world = event.world
        val chunks = backend.registeredChunks(world.uid)
        if (chunks.isEmpty()) return

        try {
            // Schedule before changing core state, so a scheduling failure can cancel safely.
            plugin.server.scheduler.runTask(plugin, Runnable {
                try {
                    if (plugin.server.getWorld(world.uid) === world) {
                        // A later listener cancelled the unload. Restore only actual live chunks.
                        restoreWorld(world)
                    } else {
                        for (chunk in chunks) backend.unregisterChunk(chunk)
                    }
                } catch (failure: Exception) {
                    plugin.logger.log(Level.SEVERE, "Failed to reconcile citadel world ${world.uid}", failure)
                }
            })

            for (chunk in chunks) {
                requireCompleted(manager.unloadChunk(world.uid, chunk.x, chunk.z))
            }
            if (chunks.any(backend::hasPendingChunkSave)) world.save()
            for (chunk in chunks) backend.unregisterChunk(chunk)
        } catch (failure: Exception) {
            event.isCancelled = true
            plugin.logger.log(Level.SEVERE, "Prevented unload of world ${world.uid}: citadel save failed", failure)
            restoreWorld(world)
        }
    }

    fun saveAll() {
        checkMainThread()
        requireCompleted(manager.saveAll())
    }

    /** Stop incoming actions and scheduler tasks before calling this during plugin shutdown. */
    fun closeAndSaveAll() {
        checkMainThread()
        try {
            requireCompleted(manager.closeAndSaveAll())
        } finally {
            backend.clearRegisteredChunks()
        }
    }

    private fun load(chunk: Chunk) {
        checkMainThread()
        if (!supported(chunk)) return
        try {
            backend.registerChunk(chunk)
            requireCompleted(manager.loadChunk(chunk.world.uid, chunk.x, chunk.z))
        } catch (failure: Exception) {
            // Do not replace unreadable state with an empty chunk. Gameplay must deny access
            // while manager.isChunkLoaded is false; the original PDC bytes stay untouched.
            report("load", chunk, failure)
        }
    }

    private fun restoreWorld(world: World) {
        for (chunk in world.loadedChunks) load(chunk)
    }

    private fun supported(chunk: Chunk): Boolean =
        chunk.x in Short.MIN_VALUE..Short.MAX_VALUE && chunk.z in Short.MIN_VALUE..Short.MAX_VALUE

    private fun requireCompleted(future: CompletableFuture<*>) {
        check(future.isDone) {
            "Paper PDC storage requires synchronous completion; use a direct codec executor"
        }
        try {
            future.join()
        } catch (failure: CompletionException) {
            throw (failure.cause as? Exception ?: failure)
        }
    }

    private fun checkMainThread() = check(plugin.server.isPrimaryThread) {
        "Chunk lifecycle access must run on the main thread"
    }

    private fun report(operation: String, chunk: Chunk, failure: Exception) {
        plugin.logger.log(
            Level.SEVERE,
            "Failed to $operation citadel data in ${chunk.world.uid} [${chunk.x}, ${chunk.z}]",
            failure
        )
    }
}
