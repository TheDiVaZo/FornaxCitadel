package ru.fornax.fornaxcitadel.core.storage

import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

/**
 * Coordinates loaded chunks, their owner-thread access, and backend persistence.
 *
 * Every location owns one [ChunkSlot]. Its FIFO queue serializes loads, updates, snapshots, and
 * unloads, while different locations remain independent. A failed unload keeps its immutable
 * snapshot in the slot until a later load, save, or close retries the write.
 */
class ChunkBackendLifecycleCoordinator(
    private val backend: DBStorage,
    private val scheduler: Scheduler,
    private val mapper: ChunkCordSnapshotMapper,
    private val codecExecutor: Executor
) : CitadelStorage {
    private val stateLock = Any()
    private val slots = HashMap<WXZCord, ChunkSlot>()
    private val operations = ChunkOperationDispatcher(stateLock, ::removeSlotIfIdleLocked)
    private var phase = Phase.ACTIVE
    private var closeAttempt: CompletableFuture<Void>? = null

    override fun getIfChunkLoaded(location: WXZCord): ChunkCord? {
        return synchronized(stateLock) {
            slots[location]?.chunk
        }
    }

    override fun loadChunk(location: WXZCord): CompletableFuture<ChunkCord> {
        return submit {
            val existingSlot = slots[location]
            val lastOperation = existingSlot?.operations?.peekLast()
            if (lastOperation?.kind == ChunkOperationKind.LOAD) {
                return@submit Submission(lastOperation.typedResult<ChunkCord>())
            }
            if (existingSlot != null && !existingSlot.hasAccepted(ChunkOperationKind.UNLOAD)) {
                existingSlot.chunk?.let {
                    return@submit Submission(CompletableFuture.completedFuture(it))
                }
            }

            val slot = existingSlot ?: ChunkSlot(location).also { slots[location] = it }
            val operation = ChunkOperation(ChunkOperationKind.LOAD, ::executeLoad)
            Submission(operation.result, operations.enqueueLocked(slot, operation))
        }
    }

    override fun unloadChunk(location: WXZCord): CompletableFuture<Void> {
        return submit {
            val slot = slots[location] ?: ChunkSlot(location).also { slots[location] = it }
            val lastOperation = slot.operations.peekLast()
            if (lastOperation?.kind == ChunkOperationKind.UNLOAD) {
                return@submit Submission(lastOperation.typedResult<Unit>())
            }

            val operation = ChunkOperation(ChunkOperationKind.UNLOAD, ::executeUnload)
            Submission(operation.result, operations.enqueueLocked(slot, operation))
        }.toVoidView()
    }

    override fun updateOpaque(chunk: ChunkCord): CompletableFuture<OpaqueUpdateResult> {
        val location = chunk.location
        return submit {
            val slot = slots[location]
            if (slot?.chunk !== chunk) {
                return@submit Submission(notLoadedInstance(location))
            }
            if (slot.hasAccepted(ChunkOperationKind.UNLOAD)) {
                return@submit Submission(unloading(location))
            }

            val operation = ChunkOperation(ChunkOperationKind.OTHER) {
                executeUpdateOpaque(it, chunk, null)
            }
            Submission(operation.result, operations.enqueueLocked(slot, operation))
        }
    }

    override fun updateOpaque(key: Key): CompletableFuture<OpaqueBatchUpdateResult> {
        val operationsToStart = ArrayList<ChunkOperation<*>>()
        val updates = ArrayList<OpaqueChunkUpdate>()

        synchronized(stateLock) {
            rejectedIfNotActive<OpaqueBatchUpdateResult>()?.let { return it }

            for (slot in slots.values) {
                val chunk = slot.chunk ?: continue
                if (slot.hasAccepted(ChunkOperationKind.UNLOAD)) continue

                val operation = ChunkOperation(ChunkOperationKind.OTHER) {
                    executeUpdateOpaque(it, chunk, key)
                }
                operations.enqueueLocked(slot, operation)?.let(operationsToStart::add)
                updates.add(OpaqueChunkUpdate(slot.location, operation.result))
            }
        }

        operationsToStart.forEach(operations::start)
        return combineOpaqueUpdateResults(updates).nonCancellingView()
    }

    override fun saveAll(): CompletableFuture<Void> {
        val batch: OperationBatch
        synchronized(stateLock) {
            rejectedIfNotActive<Void>()?.let { return it }
            batch = enqueueCheckpointsLocked(final = false)
        }

        batch.operationsToStart.forEach(operations::start)
        return combineFutureResults(batch.results)
    }

    override fun closeAndSaveAll(): CompletableFuture<Void> {
        val attempt: CompletableFuture<Void>
        val batch: OperationBatch

        synchronized(stateLock) {
            closeAttempt?.let { return it.nonCancellingView() }

            phase = Phase.DRAINING
            attempt = CompletableFuture()
            closeAttempt = attempt
            batch = enqueueCheckpointsLocked(final = true)
        }

        batch.operationsToStart.forEach(operations::start)
        combineFutureResults(batch.results)
            .thenCompose { closeBackend() }
            .whenComplete { _, error -> finishCloseAttempt(attempt, error) }

        return attempt.nonCancellingView()
    }

    /** Accepts a single operation under the state lock, then starts execution outside it. */
    private fun <T> submit(prepareLocked: () -> Submission<T>): CompletableFuture<T> {
        val submission = synchronized(stateLock) {
            rejectedIfNotActive<T>()?.let { return it }
            prepareLocked()
        }

        submission.operationToStart?.let(operations::start)
        return submission.result.nonCancellingView()
    }

    private fun enqueueCheckpointsLocked(final: Boolean): OperationBatch {
        val operationsToStart = ArrayList<ChunkOperation<*>>(slots.size)
        val results = ArrayList<CompletableFuture<*>>(slots.size)

        for (slot in slots.values) {
            val operation = ChunkOperation(ChunkOperationKind.OTHER) {
                if (final) executeUnload(it) else executeCheckpoint(it)
            }
            operations.enqueueLocked(slot, operation)?.let(operationsToStart::add)
            results.add(operation.result)
        }
        return OperationBatch(operationsToStart, results)
    }

    private fun executeLoad(slot: ChunkSlot): CompletableFuture<ChunkCord> {
        synchronized(stateLock) {
            slot.chunk?.let { return CompletableFuture.completedFuture(it) }
        }

        return retryPendingUnload(slot)
            .thenCompose { backend.loadChunk(slot.location) }
            .thenApplyAsync(
                { snapshot -> restoreChunk(slot.location, snapshot) },
                codecExecutor
            )
            .thenCompose { restored ->
                scheduler.schedule(slot.location) {
                    synchronized(stateLock) {
                        slot.chunk ?: restored.also {
                            slot.chunk = it
                        }
                    }
                }
            }
    }

    private fun executeUnload(slot: ChunkSlot): CompletableFuture<Unit> {
        val chunk = synchronized(stateLock) { slot.chunk }
            ?: return retryPendingUnload(slot)

        return scheduler.schedule(slot.location) {
            val save = captureDirtySnapshot(slot.location, chunk)
            synchronized(stateLock) {
                check(slot.chunk === chunk) {
                    "Chunk ${slot.location} is no longer the loaded instance"
                }
                check(slot.pendingUnloadSave == null) {
                    "Chunk ${slot.location} already has a pending unload snapshot"
                }
                slot.chunk = null
                slot.pendingUnloadSave = save
            }
            save
        }.thenCompose { save ->
            if (save == null) CompletableFuture.completedFuture(Unit)
            else persistPendingUnload(slot, save)
        }
    }

    private fun executeCheckpoint(slot: ChunkSlot): CompletableFuture<Unit> {
        val chunk = synchronized(stateLock) { slot.chunk }
            ?: return retryPendingUnload(slot)

        return scheduler.schedule(slot.location) {
            captureDirtySnapshot(slot.location, chunk)
        }.thenCompose { save ->
            if (save == null) CompletableFuture.completedFuture(Unit)
            else backend.saveChunk(save.snapshot).thenApply { save.markSaved() }
        }
    }

    /** Called on the owning thread, after earlier queued operations have finished. */
    private fun captureDirtySnapshot(location: WXZCord, chunk: ChunkCord): PendingChunkSave? {
        val revision = chunk.dirtyState.captureDirtyRevision() ?: return null
        val snapshot = mapper.snapshot(chunk)
        requireSnapshotLocation(location, snapshot)
        return PendingChunkSave(snapshot, chunk.dirtyState, revision)
    }

    private fun executeUpdateOpaque(
        slot: ChunkSlot,
        chunk: ChunkCord,
        key: Key?
    ): CompletableFuture<OpaqueUpdateResult> {
        return scheduler.schedule(slot.location) {
            requireLoadedInstance(slot, chunk)
            mapper.captureOpaqueComponents(chunk, key)
        }.thenCompose { candidates ->
            if (candidates.isEmpty()) {
                CompletableFuture.completedFuture(OpaqueUpdateResult())
            } else {
                CompletableFuture.supplyAsync(
                    { mapper.decodeOpaqueComponents(candidates) },
                    codecExecutor
                ).thenCompose { resolutions ->
                    scheduler.schedule(slot.location) {
                        requireLoadedInstance(slot, chunk)
                        mapper.applyOpaqueComponents(chunk, resolutions)
                    }
                }
            }
        }
    }

    private fun retryPendingUnload(slot: ChunkSlot): CompletableFuture<Unit> {
        val save = synchronized(stateLock) {
            slot.pendingUnloadSave
        } ?: return CompletableFuture.completedFuture(Unit)

        return persistPendingUnload(slot, save)
    }

    private fun persistPendingUnload(
        slot: ChunkSlot,
        save: PendingChunkSave
    ): CompletableFuture<Unit> {
        val shouldPersist = synchronized(stateLock) {
            slot.pendingUnloadSave === save
        }
        if (!shouldPersist) return CompletableFuture.completedFuture(Unit)

        return backend.saveChunk(save.snapshot).thenApply<Unit> {
            save.markSaved()
            synchronized(stateLock) {
                if (slot.pendingUnloadSave === save) {
                    slot.pendingUnloadSave = null
                }
            }
        }
    }

    private fun restoreChunk(
        location: WXZCord,
        persisted: ChunkCordSnapshot?
    ): ChunkCord {
        val snapshot = persisted ?: ChunkCordSnapshot(location)
        if (snapshot.location != location) {
            throw IOException(
                "Loaded snapshot ${snapshot.location.world} " +
                    "[${snapshot.location.x}, ${snapshot.location.z}] " +
                    "does not match requested chunk ${location.world} [${location.x}, ${location.z}]"
            )
        }
        return mapper.restore(snapshot)
    }

    private fun requireSnapshotLocation(
        location: WXZCord,
        snapshot: ChunkCordSnapshot
    ) {
        require(snapshot.location == location) {
            "Snapshot ${snapshot.location.world} " +
                "[${snapshot.location.x}, ${snapshot.location.z}] does not match " +
                "chunk ${location.world} [${location.x}, ${location.z}]"
        }
    }

    private fun requireLoadedInstance(slot: ChunkSlot, chunk: ChunkCord) {
        synchronized(stateLock) {
            check(slot.chunk === chunk) {
                "Chunk ${slot.location.world} [${slot.location.x}, ${slot.location.z}] " +
                    "is no longer loaded"
            }
        }
    }

    private fun removeSlotIfIdleLocked(slot: ChunkSlot) {
        if (slot.chunk != null || slot.pendingUnloadSave != null) return
        if (slot.running || slot.operations.isNotEmpty()) return
        if (slots[slot.location] === slot) {
            slots.remove(slot.location)
        }
    }

    private fun closeBackend(): CompletableFuture<Void> {
        return try {
            backend.close()
        } catch (exception: Exception) {
            CompletableFuture.failedFuture(exception)
        }
    }

    private fun finishCloseAttempt(
        attempt: CompletableFuture<Void>,
        error: Throwable?
    ) {
        synchronized(stateLock) {
            if (error == null) {
                phase = Phase.CLOSED
                slots.clear()
            } else if (closeAttempt === attempt) {
                closeAttempt = null
            }
        }

        if (error == null) {
            attempt.complete(null)
        } else {
            attempt.completeExceptionally(error.unwrapCompletionException())
        }
    }

    private fun combineOpaqueUpdateResults(
        updates: List<OpaqueChunkUpdate>
    ): CompletableFuture<OpaqueBatchUpdateResult> {
        if (updates.isEmpty()) {
            return CompletableFuture.completedFuture(
                OpaqueBatchUpdateResult(0, 0, 0, OpaqueUpdateResult())
            )
        }

        val result = CompletableFuture<OpaqueBatchUpdateResult>()
        CompletableFuture.allOf(*updates.map { it.result }.toTypedArray())
            .whenComplete { _, _ ->
                var components = OpaqueUpdateResult()
                var processed = 0
                val failures = LinkedHashMap<WXZCord, Throwable>()

                for (update in updates) {
                    try {
                        val chunkResult = update.result.join()
                        if (chunkResult == OpaqueUpdateResult()) continue

                        components += chunkResult
                        processed++
                    } catch (exception: CompletionException) {
                        failures[update.location] = exception.unwrapCompletionException()
                    } catch (exception: CancellationException) {
                        failures[update.location] = exception
                    }
                }

                result.complete(
                    OpaqueBatchUpdateResult(
                        chunksFound = processed + failures.size,
                        chunksProcessed = processed,
                        chunksFailed = failures.size,
                        components = components,
                        failures = failures
                    )
                )
            }
        return result
    }

    private fun <T> rejectedIfNotActive(): CompletableFuture<T>? {
        if (phase == Phase.ACTIVE) return null
        return CompletableFuture.failedFuture(
            IllegalStateException("Citadel storage is ${phase.name.lowercase()}")
        )
    }

    private fun <T> notLoadedInstance(location: WXZCord): CompletableFuture<T> {
        return CompletableFuture.failedFuture(
            IllegalStateException(
                "Chunk ${location.world} [${location.x}, ${location.z}] is not the loaded instance"
            )
        )
    }

    private fun <T> unloading(location: WXZCord): CompletableFuture<T> {
        return CompletableFuture.failedFuture(
            IllegalStateException(
                "Chunk ${location.world} [${location.x}, ${location.z}] is unloading"
            )
        )
    }

    private fun CompletableFuture<*>.toVoidView(): CompletableFuture<Void> {
        return thenApply<Void> { null }
    }

    private enum class Phase {
        ACTIVE,
        DRAINING,
        CLOSED
    }

    private data class Submission<T>(
        val result: CompletableFuture<T>,
        val operationToStart: ChunkOperation<*>? = null
    )

    private data class OperationBatch(
        val operationsToStart: List<ChunkOperation<*>>,
        val results: List<CompletableFuture<*>>
    )

    private data class OpaqueChunkUpdate(
        val location: WXZCord,
        val result: CompletableFuture<OpaqueUpdateResult>
    )

}
