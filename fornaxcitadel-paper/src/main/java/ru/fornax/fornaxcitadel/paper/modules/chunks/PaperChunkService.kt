package ru.fornax.fornaxcitadel.paper.modules.chunks

import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.event.HandlerList
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.paper.storage.PersistContainerChunkStorage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.logging.Level

/** Binds reusable core storage to live Bukkit chunks on the main thread. */
class PaperChunkService(
    private val plugin: Plugin,
    private val manager: SimpleComponentManager,
    private val backend: PersistContainerChunkStorage,
    private val saveIntervalTicks: Long
) : LifecycleService {
    private val listener = PaperChunkListener(this)
    private val reconciliations = mutableSetOf<Reconciliation>()
    private var generation: Any? = null
    private var autosave: BukkitTask? = null
    private var cleanupPending = false

    init {
        require(saveIntervalTicks > 0) { "Save interval must be positive" }
    }

    override fun enable() {
        checkMainThread()
        if (generation != null) return
        if (cleanupPending) suspendChunks()
        val current = Any()
        generation = current
        cleanupPending = true
        try {
            plugin.server.pluginManager.registerEvents(listener, plugin)
            for (world in plugin.server.worlds) restoreWorld(world)
            autosave = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                if (generation !== current) return@Runnable
                try {
                    saveAll()
                } catch (failure: Exception) {
                    plugin.logger.log(Level.SEVERE, "Could not save citadel data to loaded chunks", failure)
                }
            }, saveIntervalTicks, saveIntervalTicks)
        } catch (failure: Exception) {
            try {
                disable()
            } catch (cleanupFailure: Exception) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    /** Stops subscriptions and saves/unloads data while leaving core storage reusable. */
    override fun disable() {
        checkMainThread()
        if (generation == null && !cleanupPending) return
        generation = null
        HandlerList.unregisterAll(listener)
        autosave?.cancel()
        autosave = null
        val pending = reconciliations.toList()
        reconciliations.clear()
        pending.forEach { it.task?.cancel() }
        suspendChunks()
    }

    /** Saves live data and retries failed unload snapshots; storage is not closed. */
    fun saveAll() {
        checkMainThread()
        requireCompleted(manager.saveAll())
    }

    fun onLoad(event: ChunkLoadEvent) {
        checkMainThread()
        if (generation != null) load(event.chunk)
    }

    fun onUnload(event: ChunkUnloadEvent) {
        checkMainThread()
        if (generation == null) return
        val chunk = event.chunk
        if (!supported(chunk)) return
        var saved = false
        try {
            requireCompleted(manager.unloadChunk(chunk.world.uid, chunk.x, chunk.z))
            saved = true
        } catch (failure: Exception) {
            // Keep the backend binding and core's pending snapshot for a later retry.
            report("save and unload", chunk, failure)
        } finally {
            if (backend.hasPendingChunkSave(chunk)) event.isSaveChunk = true
            if (saved) backend.unregisterChunk(chunk)
        }
    }

    /** Flushes before world removal and reconciles a later cancellation on the next tick. */
    fun onWorldUnload(event: WorldUnloadEvent) {
        checkMainThread()
        val current = generation ?: return
        val world = event.world
        val chunks = backend.registeredChunks(world.uid)
        if (chunks.isEmpty()) return

        try {
            val reconciliation = Reconciliation()
            reconciliations.add(reconciliation)
            try {
                reconciliation.task = plugin.server.scheduler.runTask(plugin, Runnable {
                    if (generation !== current || !reconciliations.remove(reconciliation)) return@Runnable
                    try {
                        if (plugin.server.getWorld(world.uid) === world) {
                            restoreWorld(world)
                        } else {
                            chunks.forEach(backend::unregisterChunk)
                        }
                    } catch (failure: Exception) {
                        plugin.logger.log(Level.SEVERE, "Failed to reconcile citadel world ${world.uid}", failure)
                    }
                })
            } catch (failure: Exception) {
                reconciliations.remove(reconciliation)
                throw failure
            }

            for (chunk in chunks) {
                requireCompleted(manager.unloadChunk(world.uid, chunk.x, chunk.z))
            }
            if (chunks.any(backend::hasPendingChunkSave)) world.save()
            chunks.forEach(backend::unregisterChunk)
        } catch (failure: Exception) {
            event.isCancelled = true
            plugin.logger.log(Level.SEVERE, "Prevented unload of world ${world.uid}: citadel save failed", failure)
            restoreWorld(world)
        }
    }

    private fun suspendChunks() {
        var failure: Exception? = null
        for (chunk in backend.registeredChunks()) {
            try {
                requireCompleted(manager.unloadChunk(chunk.world.uid, chunk.x, chunk.z))
            } catch (problem: Exception) {
                if (failure == null) failure = problem else if (failure !== problem) failure.addSuppressed(problem)
            }
        }
        try {
            // Without the listener, a later save=false unload would otherwise discard PDC writes.
            backend.flushPendingWorldSaves()
        } catch (problem: Exception) {
            if (failure == null) failure = problem else if (failure !== problem) failure.addSuppressed(problem)
        }
        if (failure != null) throw failure
        backend.clearRegisteredChunks()
        cleanupPending = false
    }

    private fun load(chunk: Chunk) {
        if (!supported(chunk)) return
        try {
            backend.registerChunk(chunk)
            requireCompleted(manager.loadChunk(chunk.world.uid, chunk.x, chunk.z))
        } catch (failure: Exception) {
            // Unreadable chunks stay unavailable; their original PDC bytes are not replaced.
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
        plugin.logger.log(Level.SEVERE,
            "Failed to $operation citadel data in ${chunk.world.uid} [${chunk.x}, ${chunk.z}]", failure)
    }

    private class Reconciliation {
        var task: BukkitTask? = null
    }
}
