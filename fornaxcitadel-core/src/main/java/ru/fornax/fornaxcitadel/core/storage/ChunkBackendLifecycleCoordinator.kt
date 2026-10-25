package ru.fornax.fornaxcitadel.core.storage

import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Coordinates the lifecycle of loaded chunks and exposes them through [CitadelStorage].
 *
 * Load and unload requests are serialized per chunk location, duplicate loads are shared,
 * live chunks are published and snapshotted on the owning game thread through [Scheduler],
 * and snapshot restoration is delegated to the configured codec executor. Persistence and
 * retry ordering are handled by [ChunkSaveCoordinator].
 */
class ChunkBackendLifecycleCoordinator(
    private val backend: DBStorage,
    private val scheduler: Scheduler,
    private val mapper: ChunkCordSnapshotMapper,
    private val codecExecutor: Executor
) : CitadelStorage {
    private val stateLock = Any()
    private val loadedChunks = HashMap<WXZCord, ChunkCord>()
    private val lifecycleStates = HashMap<WXZCord, LifecycleState>()
    private val saves = ChunkSaveCoordinator(backend)

    override fun getIfChunkLoaded(location: WXZCord): ChunkCord? {
        val key = location.copyLocation()
        return synchronized(stateLock) {
            loadedChunks[key]
        }
    }

    override fun loadChunk(location: WXZCord): CompletableFuture<ChunkCord> {
        val key = location.copyLocation()
        var operationToStart: LifecycleOperation? = null
        val result: CompletableFuture<ChunkCord>

        synchronized(stateLock) {
            val state = lifecycleStates[key]
            val lastOperation = state?.operations?.peekLast()
            if (lastOperation is LoadOperation) {
                return lastOperation.result.nonCancellingView()
            }

            if (lastOperation == null) {
                val loaded = loadedChunks[key]
                if (loaded != null) return CompletableFuture.completedFuture(loaded)
            }

            val operation = LoadOperation()
            val lifecycleState = state ?: LifecycleState().also {
                lifecycleStates[key] = it
            }
            lifecycleState.operations.addLast(operation)
            if (!lifecycleState.running) {
                lifecycleState.running = true
                operationToStart = operation
            }
            result = operation.result
        }

        operationToStart?.let { startLifecycleOperation(key, it) }
        return result.nonCancellingView()
    }

    override fun unloadChunk(location: WXZCord): CompletableFuture<Void> {
        val key = location.copyLocation()
        var operationToStart: LifecycleOperation? = null
        val result: CompletableFuture<Void>

        synchronized(stateLock) {
            val state = lifecycleStates[key]
            val lastOperation = state?.operations?.peekLast()
            if (lastOperation is UnloadOperation) {
                return lastOperation.result.nonCancellingView()
            }

            val operation = UnloadOperation((lastOperation as? LoadOperation)?.result)
            val lifecycleState = state ?: LifecycleState().also {
                lifecycleStates[key] = it
            }
            lifecycleState.operations.addLast(operation)
            if (!lifecycleState.running) {
                lifecycleState.running = true
                operationToStart = operation
            }
            result = operation.result
        }

        operationToStart?.let { startLifecycleOperation(key, it) }
        return result.nonCancellingView()
    }

    override fun saveAll(): CompletableFuture<Void> {
        val snapshotRequests = ArrayList<SnapshotRequest>()
        val batch = synchronized(stateLock) {
            val lifecycleSaves = HashMap<WXZCord, List<CompletableFuture<Void>>>()
            for ((location, state) in lifecycleStates) {
                val unloads = state.operations
                    .filterIsInstance<UnloadOperation>()
                    .map(UnloadOperation::result)
                if (unloads.isNotEmpty()) {
                    lifecycleSaves[location] = unloads
                }
            }

            val liveSnapshots = HashMap<WXZCord, CompletableFuture<ChunkCordSnapshot>>()
            for ((location, chunk) in loadedChunks) {
                if (location in lifecycleSaves) continue

                val snapshot = CompletableFuture<ChunkCordSnapshot>()
                liveSnapshots[location] = snapshot
                snapshotRequests.add(SnapshotRequest(location, chunk, snapshot))
            }

            saves.reserveSaveAll(liveSnapshots, lifecycleSaves)
        }

        val result = saves.startSaveAll(batch)
        snapshotRequests.forEach(::scheduleSnapshot)
        return result
    }

    private fun startLifecycleOperation(
        location: WXZCord,
        operation: LifecycleOperation
    ) {
        val execution = try {
            when (operation) {
                is LoadOperation -> executeLoad(location)
                is UnloadOperation -> {
                    val requiredLoad = operation.requiredLoad
                    if (requiredLoad == null) {
                        executeUnload(location)
                    } else {
                        requiredLoad.thenCompose { executeUnload(location) }
                    }
                }
            }
        } catch (exception: Exception) {
            CompletableFuture.failedFuture(exception)
        }

        execution.whenComplete { value, error ->
            val next = removeLifecycleOperation(location, operation)
            when (operation) {
                is LoadOperation -> {
                    @Suppress("UNCHECKED_CAST")
                    complete(operation.result, value as ChunkCord?, error)
                }
                is UnloadOperation -> complete(operation.result, null, error)
            }
            next?.let { startLifecycleOperation(location, it) }
        }
    }

    private fun executeLoad(location: WXZCord): CompletableFuture<ChunkCord> {
        return saves.awaitLatestOrRetry(location)
            .thenCompose { backend.loadChunk(location) }
            .thenApplyAsync(
                { snapshot -> restoreChunk(location, snapshot) },
                codecExecutor
            )
            .thenCompose { chunk ->
                scheduler.schedule(location) {
                    synchronized(stateLock) {
                        loadedChunks[location]
                            ?: chunk.also { loadedChunks[location] = it }
                    }
                }
            }
    }

    private fun executeUnload(location: WXZCord): CompletableFuture<Void> {
        val chunk = synchronized(stateLock) {
            loadedChunks[location]
        } ?: return saves.awaitLatestOrRetry(location)

        val snapshot = CompletableFuture<ChunkCordSnapshot>()
        val result = saves.saveForUnload(location, snapshot)
        completeFromScheduledSnapshot(
            location,
            snapshot
        ) {
            val value = mapper.snapshot(chunk)
            synchronized(stateLock) {
                if (loadedChunks[location] === chunk) {
                    loadedChunks.remove(location)
                }
            }
            value
        }
        return result
    }

    private fun restoreChunk(
        location: WXZCord,
        persisted: ChunkCordSnapshot?
    ): ChunkCord {
        val snapshot = persisted ?: ChunkCordSnapshot(
            location.world,
            location.x,
            location.z
        )
        if (snapshot != location) {
            throw IOException(
                "Loaded snapshot ${snapshot.world} [${snapshot.x}, ${snapshot.z}] " +
                    "does not match requested chunk ${location.world} [${location.x}, ${location.z}]"
            )
        }
        return mapper.restore(snapshot)
    }

    private fun scheduleSnapshot(request: SnapshotRequest) {
        completeFromScheduledSnapshot(request.location, request.result) {
            mapper.snapshot(request.chunk)
        }
    }

    private fun completeFromScheduledSnapshot(
        location: WXZCord,
        result: CompletableFuture<ChunkCordSnapshot>,
        snapshot: () -> ChunkCordSnapshot
    ) {
        val scheduled = try {
            scheduler.schedule(location) { snapshot() }
        } catch (exception: Exception) {
            result.completeExceptionally(exception)
            return
        }

        scheduled.whenComplete { value, error ->
            if (error == null) {
                result.complete(value)
            } else {
                result.completeExceptionally(error.unwrapCompletionException())
            }
        }
    }

    private fun removeLifecycleOperation(
        location: WXZCord,
        operation: LifecycleOperation
    ): LifecycleOperation? {
        return synchronized(stateLock) {
            val state = checkNotNull(lifecycleStates[location])
            check(state.operations.peekFirst() === operation)
            state.operations.removeFirst()

            val next = state.operations.peekFirst()
            if (next == null) {
                state.running = false
                lifecycleStates.remove(location)
            }
            next
        }
    }

    private fun <T> complete(
        result: CompletableFuture<T>,
        value: T?,
        error: Throwable?
    ) {
        if (error == null) {
            @Suppress("UNCHECKED_CAST")
            result.complete(value as T)
        } else {
            result.completeExceptionally(error.unwrapCompletionException())
        }
    }

    private fun WXZCord.copyLocation(): WXZCord {
        return WXZCord(world, x, z)
    }

    private sealed class LifecycleOperation

    private class LoadOperation : LifecycleOperation() {
        val result = CompletableFuture<ChunkCord>()
    }

    private class UnloadOperation(
        val requiredLoad: CompletableFuture<ChunkCord>?
    ) : LifecycleOperation() {
        val result = CompletableFuture<Void>()
    }

    private class LifecycleState {
        val operations = ArrayDeque<LifecycleOperation>()
        var running = false
    }

    private class SnapshotRequest(
        val location: WXZCord,
        val chunk: ChunkCord,
        val result: CompletableFuture<ChunkCordSnapshot>
    )
}
