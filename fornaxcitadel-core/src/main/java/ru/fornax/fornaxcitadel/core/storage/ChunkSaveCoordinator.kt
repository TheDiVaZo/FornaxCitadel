package ru.fornax.fornaxcitadel.core.storage

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Coordinates persistence of immutable [ChunkCordSnapshot] instances.
 *
 * Saves are executed in FIFO order for each chunk location while different locations may be
 * persisted independently. Failed unload snapshots are retained for retry, and save-all
 * operations wait for both newly scheduled saves and writes that were already in flight.
 */
internal class ChunkSaveCoordinator(
    private val backend: DBStorage
) {
    private val stateLock = Any()
    private val saveTails = HashMap<WXZCord, CompletableFuture<Void>>()
    private val pendingUnloads = HashMap<WXZCord, PendingUnload>()

    fun saveForUnload(
        location: WXZCord,
        snapshot: CompletableFuture<ChunkCordSnapshot>
    ): CompletableFuture<Void> {
        val queuedSave = synchronized(stateLock) {
            reserveSaveLocked(location) { result ->
                snapshot.thenCompose { value ->
                    requireLocation(location, value)
                    val pending = PendingUnload(value, result)
                    synchronized(stateLock) {
                        check(pendingUnloads[location] == null) {
                            "A pending unload already exists for $location"
                        }
                        pendingUnloads[location] = pending
                    }
                    persistPending(location, pending)
                }
            }
        }

        startQueuedSave(queuedSave)
        return queuedSave.result
    }

    fun awaitLatestOrRetry(location: WXZCord): CompletableFuture<Void> {
        var queuedSave: QueuedSave? = null
        val result = synchronized(stateLock) {
            val pending = pendingUnloads[location]
            if (pending == null) {
                saveTails[location] ?: completedVoid()
            } else {
                val activeSave = pending.activeSave
                if (!activeSave.isDone) {
                    activeSave
                } else {
                    reservePendingSaveLocked(location, pending).also {
                        queuedSave = it
                    }.result
                }
            }
        }

        queuedSave?.let(::startQueuedSave)
        return result
    }

    fun reserveSaveAll(
        liveSnapshots: Map<WXZCord, CompletableFuture<ChunkCordSnapshot>>,
        lifecycleSaves: Map<WXZCord, List<CompletableFuture<Void>>>
    ): SaveBatch {
        return synchronized(stateLock) {
            val queuedSaves = ArrayList<QueuedSave>()
            val saves = ArrayList<CompletableFuture<Void>>()
            val existingTails = HashMap(saveTails)
            val coveredLocations = HashSet<WXZCord>()

            for ((location, requiredSaves) in lifecycleSaves) {
                coveredLocations.add(location)
                saves.addAll(requiredSaves)
            }

            for ((location, pending) in pendingUnloads) {
                if (!coveredLocations.add(location)) continue

                val activeSave = pending.activeSave
                if (!activeSave.isDone) {
                    saves.add(activeSave)
                } else {
                    val queuedSave = reservePendingSaveLocked(location, pending)
                    queuedSaves.add(queuedSave)
                    saves.add(queuedSave.result)
                }
            }

            for ((location, snapshot) in liveSnapshots) {
                if (!coveredLocations.add(location)) continue

                val queuedSave = reserveSaveLocked(location) {
                    snapshot.thenCompose { value ->
                        requireLocation(location, value)
                        backend.saveChunk(value)
                    }
                }
                queuedSaves.add(queuedSave)
                saves.add(queuedSave.result)
            }

            for ((location, tail) in existingTails) {
                if (location !in coveredLocations) {
                    saves.add(tail)
                }
            }

            SaveBatch(queuedSaves, saves)
        }
    }

    fun startSaveAll(batch: SaveBatch): CompletableFuture<Void> {
        batch.queuedSaves.forEach(::startQueuedSave)
        return combineSaveResults(batch.saves)
    }

    private fun reservePendingSaveLocked(
        location: WXZCord,
        pending: PendingUnload
    ): QueuedSave {
        val queuedSave = reserveSaveLocked(location) {
            persistPending(location, pending)
        }
        pending.activeSave = queuedSave.result
        return queuedSave
    }

    private fun persistPending(
        location: WXZCord,
        pending: PendingUnload
    ): CompletableFuture<Void> {
        val shouldPersist = synchronized(stateLock) {
            pendingUnloads[location] === pending
        }
        if (!shouldPersist) return completedVoid()

        return backend.saveChunk(pending.snapshot).thenRun {
            synchronized(stateLock) {
                if (pendingUnloads[location] === pending) {
                    pendingUnloads.remove(location)
                }
            }
        }
    }

    private fun reserveSaveLocked(
        location: WXZCord,
        operation: (CompletableFuture<Void>) -> CompletableFuture<Void>
    ): QueuedSave {
        val result = CompletableFuture<Void>()
        val predecessor = saveTails.put(location, result) ?: completedVoid()
        return QueuedSave(
            location,
            predecessor,
            result
        ) { operation(result) }
    }

    private fun startQueuedSave(queuedSave: QueuedSave) {
        val execution = queuedSave.predecessor
            .handle { _, _ -> Unit }
            .thenCompose { queuedSave.operation() }

        execution.whenComplete { _, error ->
            if (error == null) {
                queuedSave.result.complete(null)
            } else {
                queuedSave.result.completeExceptionally(error.unwrapCompletionException())
            }

            synchronized(stateLock) {
                if (saveTails[queuedSave.location] === queuedSave.result) {
                    saveTails.remove(queuedSave.location)
                }
            }
        }
    }

    private fun combineSaveResults(
        saves: List<CompletableFuture<Void>>
    ): CompletableFuture<Void> {
        if (saves.isEmpty()) return completedVoid()

        val result = CompletableFuture<Void>()
        CompletableFuture.allOf(*saves.toTypedArray()).whenComplete { _, _ ->
            try {
                val failures = saves.mapNotNull(::getFailure)
                when (failures.size) {
                    0 -> result.complete(null)
                    1 -> result.completeExceptionally(failures.first())
                    else -> {
                        val aggregate = IllegalStateException(
                            "${failures.size} chunk save operations failed",
                            failures.first()
                        )
                        failures.drop(1).forEach(aggregate::addSuppressed)
                        result.completeExceptionally(aggregate)
                    }
                }
            } catch (exception: Exception) {
                result.completeExceptionally(exception)
            }
        }
        return result
    }

    private fun getFailure(future: CompletableFuture<Void>): Throwable? {
        return try {
            future.join()
            null
        } catch (exception: CompletionException) {
            exception.unwrapCompletionException()
        } catch (exception: CancellationException) {
            exception
        }
    }

    private fun requireLocation(
        location: WXZCord,
        snapshot: ChunkCordSnapshot
    ) {
        require(snapshot == location) {
            "Snapshot ${snapshot.world} [${snapshot.x}, ${snapshot.z}] does not match " +
                "chunk ${location.world} [${location.x}, ${location.z}]"
        }
    }

    internal class SaveBatch(
        val queuedSaves: List<QueuedSave>,
        val saves: List<CompletableFuture<Void>>
    )

    private class PendingUnload(
        val snapshot: ChunkCordSnapshot,
        var activeSave: CompletableFuture<Void>
    )

    internal class QueuedSave(
        val location: WXZCord,
        val predecessor: CompletableFuture<Void>,
        val result: CompletableFuture<Void>,
        val operation: () -> CompletableFuture<Void>
    )
}
