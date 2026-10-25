package ru.fornax.fornaxcitadel.core.storage

import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChunkBackendLifecycleCoordinatorTest {

    @Test
    fun `concurrent loads for the same location share one backend read`() {
        val backend = FakeDBStorage()
        val backendLoad = CompletableFuture<ChunkCordSnapshot?>()
        backend.enqueueLoad(backendLoad)
        val storage = createStorage(backend)
        val location = location(3, -7)

        val first = storage.loadChunk(location)
        val second = storage.loadChunk(WXZCord(location.world, location.x, location.z))

        assertEquals(listOf(location), backend.loadCalls)
        assertFalse(first.isDone)
        assertFalse(second.isDone)

        backendLoad.complete(null)

        val loaded = first.join()
        assertSame(loaded, second.join())
        assertSame(loaded, storage.getIfChunkLoaded(location))
        assertEquals(location.world, loaded.world)
        assertEquals(location.x, loaded.x)
        assertEquals(location.z, loaded.z)
    }

    @Test
    fun `backend snapshot is restored before scheduler publication`() {
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val location = location(8, 11)
        val persisted = ChunkCord(location.world, location.x, location.z).apply {
            putCitadel(5, 73, 9)
        }
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(mapper.snapshot(persisted)))
        }
        val codecExecutor = ControlledExecutor()
        val scheduler = ControlledScheduler()
        val storage = ChunkBackendLifecycleCoordinator(backend, scheduler, mapper, codecExecutor)

        val loading = storage.loadChunk(location)

        assertEquals(1, codecExecutor.pendingCount)
        assertEquals(0, scheduler.pendingCount)
        assertNull(storage.getIfChunkLoaded(location))
        assertFalse(loading.isDone)

        codecExecutor.runNext()

        assertEquals(0, codecExecutor.pendingCount)
        assertEquals(1, scheduler.pendingCount)
        assertEquals(location, scheduler.nextLocation)
        assertNull(storage.getIfChunkLoaded(location))
        assertFalse(loading.isDone)

        scheduler.runNext()

        val loaded = loading.join()
        assertSame(loaded, storage.getIfChunkLoaded(location))
        assertNotNull(loaded.getCitadel(5, 73, 9))
    }

    @Test
    fun `unload serializes on scheduler and completes after its FIFO write`() {
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(null))
        }
        val scheduler = ControlledScheduler()
        val storage = ChunkBackendLifecycleCoordinator(backend, scheduler, mapper, DIRECT_EXECUTOR)
        val location = location(-4, 19)
        val loading = storage.loadChunk(location)
        scheduler.runNext()
        val loaded = loading.join().apply {
            putCitadel(2, 40, 14)
        }
        val backendSave = CompletableFuture<Void>()
        backend.enqueueSave(backendSave)

        val unloading = storage.unloadChunk(location)

        assertEquals(1, scheduler.pendingCount)
        assertTrue(backend.saveCalls.isEmpty())
        assertSame(loaded, storage.getIfChunkLoaded(location))
        assertFalse(unloading.isDone)

        scheduler.runNext()

        assertEquals(1, backend.saveCalls.size)
        val save = backend.saveCalls.single()
        assertEquals(location, save)
        assertNotNull(mapper.restore(save).getCitadel(2, 40, 14))
        assertNull(storage.getIfChunkLoaded(location))
        assertFalse(unloading.isDone)

        backendSave.complete(null)

        assertNull(unloading.join())
    }

    @Test
    fun `load requested during unload waits for write and publishes a fresh chunk`() {
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val backendSave = CompletableFuture<Void>()
        val backendReload = CompletableFuture<ChunkCordSnapshot?>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(null))
            enqueueLoad(backendReload)
            enqueueSave(backendSave)
        }
        val scheduler = ControlledScheduler()
        val storage = ChunkBackendLifecycleCoordinator(backend, scheduler, mapper, DIRECT_EXECUTOR)
        val location = location(6, -18)
        val initialLoading = storage.loadChunk(location)
        scheduler.runNext()
        val original = initialLoading.join().apply {
            putCitadel(9, 64, 3)
        }

        val unloading = storage.unloadChunk(location)
        val reloading = storage.loadChunk(location)

        assertFalse(unloading.isDone)
        assertFalse(reloading.isDone)
        assertEquals(1, backend.loadCalls.size)
        assertSame(original, storage.getIfChunkLoaded(location))

        scheduler.runNext()

        assertEquals(1, backend.saveCalls.size)
        assertNull(storage.getIfChunkLoaded(location))
        assertFalse(unloading.isDone)
        assertFalse(reloading.isDone)
        assertEquals(1, backend.loadCalls.size)

        backendSave.complete(null)

        assertNull(unloading.join())
        assertEquals(2, backend.loadCalls.size)
        assertFalse(reloading.isDone)

        backendReload.complete(backend.saveCalls.single())

        assertEquals(1, scheduler.pendingCount)
        assertFalse(reloading.isDone)
        scheduler.runNext()

        val reloaded = reloading.join()
        assertNotSame(original, reloaded)
        assertSame(reloaded, storage.getIfChunkLoaded(location))
        assertNotNull(reloaded.getCitadel(9, 64, 3))
    }

    @Test
    fun `unload during initial load makes a subsequent load wait for reload`() {
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val persisted = ChunkCord(WORLD_ID, -9, 27).apply {
            putCitadel(4, 22, 12)
        }
        val initialBackendLoad = CompletableFuture<ChunkCordSnapshot?>()
        val backendSave = CompletableFuture<Void>()
        val backendReload = CompletableFuture<ChunkCordSnapshot?>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(initialBackendLoad)
            enqueueLoad(backendReload)
            enqueueSave(backendSave)
        }
        val scheduler = ControlledScheduler()
        val storage = ChunkBackendLifecycleCoordinator(backend, scheduler, mapper, DIRECT_EXECUTOR)
        val location = WXZCord(persisted.world, persisted.x, persisted.z)

        val initialLoading = storage.loadChunk(location)
        val unloading = storage.unloadChunk(location)
        val subsequentLoading = storage.loadChunk(location)

        assertEquals(1, backend.loadCalls.size)
        assertFalse(initialLoading.isDone)
        assertFalse(unloading.isDone)
        assertFalse(subsequentLoading.isDone)

        initialBackendLoad.complete(mapper.snapshot(persisted))

        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()

        val initiallyLoaded = initialLoading.join()
        assertFalse(unloading.isDone)
        assertFalse(subsequentLoading.isDone)
        assertEquals(1, scheduler.pendingCount)

        scheduler.runNext()

        assertEquals(1, backend.saveCalls.size)
        assertFalse(unloading.isDone)
        assertFalse(subsequentLoading.isDone)

        backendSave.complete(null)

        assertNull(unloading.join())
        assertEquals(2, backend.loadCalls.size)
        assertFalse(subsequentLoading.isDone)

        backendReload.complete(backend.saveCalls.single())

        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()

        val subsequentlyLoaded = subsequentLoading.join()
        assertNotSame(initiallyLoaded, subsequentlyLoaded)
        assertSame(subsequentlyLoaded, storage.getIfChunkLoaded(location))
        assertNotNull(subsequentlyLoaded.getCitadel(4, 22, 12))
    }

    @Test
    fun `saveAll waits for unload accepted during initial load`() {
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val persisted = ChunkCord(WORLD_ID, 15, -30).apply {
            putCitadel(6, 48, 10)
        }
        val initialBackendLoad = CompletableFuture<ChunkCordSnapshot?>()
        val backendSave = CompletableFuture<Void>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(initialBackendLoad)
            enqueueSave(backendSave)
        }
        val scheduler = ControlledScheduler()
        val storage = ChunkBackendLifecycleCoordinator(backend, scheduler, mapper, DIRECT_EXECUTOR)
        val location = WXZCord(persisted.world, persisted.x, persisted.z)

        val initialLoading = storage.loadChunk(location)
        val unloading = storage.unloadChunk(location)
        val saving = storage.saveAll()

        assertFalse(initialLoading.isDone)
        assertFalse(unloading.isDone)
        assertFalse(saving.isDone)
        assertTrue(backend.saveCalls.isEmpty())

        initialBackendLoad.complete(mapper.snapshot(persisted))

        assertEquals(1, scheduler.pendingCount)
        assertFalse(initialLoading.isDone)
        assertFalse(unloading.isDone)
        assertFalse(saving.isDone)

        scheduler.runNext()

        assertNotNull(initialLoading.join().getCitadel(6, 48, 10))
        assertEquals(1, scheduler.pendingCount)
        assertFalse(unloading.isDone)
        assertFalse(saving.isDone)
        assertTrue(backend.saveCalls.isEmpty())

        scheduler.runNext()

        assertEquals(1, backend.saveCalls.size)
        assertNull(storage.getIfChunkLoaded(location))
        assertFalse(unloading.isDone)
        assertFalse(saving.isDone)

        backendSave.complete(null)

        assertNull(unloading.join())
        assertNull(saving.join())
    }

    @Test
    fun `failed unload bytes are retained and retried by saveAll`() {
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val firstFailure = IllegalStateException("first write failed")
        val failedSave = CompletableFuture<Void>()
        failedSave.completeExceptionally(firstFailure)
        val retrySave = CompletableFuture<Void>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(null))
            enqueueSave(failedSave)
            enqueueSave(retrySave)
        }
        val storage = createStorage(backend, mapper)
        val location = location(12, -21)
        storage.loadChunk(location).join().putCitadel(7, 101, 4)

        val unloading = storage.unloadChunk(location)

        val exception = assertFailsWith<CompletionException> { unloading.join() }
        assertSame(firstFailure, exception.cause)
        assertNull(storage.getIfChunkLoaded(location))
        assertEquals(1, backend.saveCalls.size)

        val saving = storage.saveAll()

        assertEquals(2, backend.saveCalls.size)
        assertEquals(location, backend.saveCalls[1])
        assertSame(backend.saveCalls[0], backend.saveCalls[1])
        assertFalse(saving.isDone)

        retrySave.complete(null)

        assertNull(saving.join())
    }

    @Test
    fun `two saves for the same location reach backend in FIFO order`() {
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val firstBackendSave = CompletableFuture<Void>()
        val secondBackendSave = CompletableFuture<Void>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(null))
            enqueueSave(firstBackendSave)
            enqueueSave(secondBackendSave)
        }
        val storage = createStorage(backend, mapper)
        val location = location(1, 2)
        val loaded = storage.loadChunk(location).join()
        loaded.putCitadel(1, 0, 1)

        val firstSave = storage.saveAll()
        assertEquals(1, backend.saveCalls.size)

        loaded.putCitadel(2, 0, 2)
        val secondSave = storage.saveAll()

        assertEquals(1, backend.saveCalls.size)
        assertFalse(firstSave.isDone)
        assertFalse(secondSave.isDone)

        firstBackendSave.complete(null)

        assertNull(firstSave.join())
        assertEquals(2, backend.saveCalls.size)
        assertEquals(1, backend.saveCalls[0].citadels.size)
        assertEquals(2, backend.saveCalls[1].citadels.size)
        assertFalse(secondSave.isDone)

        secondBackendSave.complete(null)

        assertNull(secondSave.join())
    }

    @Test
    fun `saveAll waits for existing in-flight writes and newly loaded chunk saves`() {
        val firstLocation = location(31, 32)
        val secondLocation = location(41, 42)
        val inFlightUnload = CompletableFuture<Void>()
        val loadedChunkSave = CompletableFuture<Void>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(null))
            enqueueLoad(CompletableFuture.completedFuture(null))
            enqueueSave(inFlightUnload)
            enqueueSave(loadedChunkSave)
        }
        val storage = createStorage(backend)
        storage.loadChunk(firstLocation).join().putCitadel(3, 15, 3)
        storage.loadChunk(secondLocation).join().putCitadel(4, 16, 4)

        val unloading = storage.unloadChunk(firstLocation)
        val saving = storage.saveAll()

        assertEquals(
            listOf(firstLocation, secondLocation),
            backend.saveCalls.map { WXZCord(it.world, it.x, it.z) }
        )
        assertFalse(unloading.isDone)
        assertFalse(saving.isDone)

        loadedChunkSave.complete(null)

        assertFalse(saving.isDone)
        assertFalse(unloading.isDone)

        inFlightUnload.complete(null)

        assertNull(unloading.join())
        assertNull(saving.join())
    }

    private fun createStorage(
        backend: DBStorage,
        mapper: ChunkCordSnapshotMapper = ChunkCordSnapshotMapper(TypeManager())
    ): ChunkBackendLifecycleCoordinator {
        return ChunkBackendLifecycleCoordinator(backend, DirectScheduler, mapper, DIRECT_EXECUTOR)
    }

    private fun location(x: Int, z: Int): WXZCord {
        return WXZCord(WORLD_ID, x, z)
    }

    private class FakeDBStorage : DBStorage {
        private val loads = ArrayDeque<CompletableFuture<ChunkCordSnapshot?>>()
        private val saves = ArrayDeque<CompletableFuture<Void>>()

        val loadCalls = ArrayList<WXZCord>()
        val saveCalls = ArrayList<ChunkCordSnapshot>()

        fun enqueueLoad(result: CompletableFuture<ChunkCordSnapshot?>) {
            loads.addLast(result)
        }

        fun enqueueSave(result: CompletableFuture<Void>) {
            saves.addLast(result)
        }

        override fun loadChunk(location: WXZCord): CompletableFuture<ChunkCordSnapshot?> {
            loadCalls.add(location)
            check(loads.isNotEmpty()) { "No load result configured for $location" }
            return loads.removeFirst()
        }

        override fun saveChunk(
            snapshot: ChunkCordSnapshot
        ): CompletableFuture<Void> {
            saveCalls.add(snapshot)
            check(saves.isNotEmpty()) { "No save result configured for $snapshot" }
            return saves.removeFirst()
        }
    }

    private object DirectScheduler : Scheduler {
        override fun <T> schedule(
            location: WXZCord,
            task: Callable<T>
        ): CompletableFuture<T> {
            return try {
                CompletableFuture.completedFuture(task.call())
            } catch (exception: Throwable) {
                CompletableFuture.failedFuture(exception)
            }
        }
    }

    private class ControlledScheduler : Scheduler {
        private val scheduled = ArrayDeque<ScheduledCall>()

        val pendingCount: Int
            get() = scheduled.size

        val nextLocation: WXZCord?
            get() = scheduled.peekFirst()?.location

        override fun <T> schedule(
            location: WXZCord,
            task: Callable<T>
        ): CompletableFuture<T> {
            val result = CompletableFuture<T>()
            scheduled.addLast(
                ScheduledCall(location) {
                    try {
                        result.complete(task.call())
                    } catch (exception: Throwable) {
                        result.completeExceptionally(exception)
                    }
                }
            )
            return result
        }

        fun runNext() {
            check(scheduled.isNotEmpty()) { "No scheduled task" }
            scheduled.removeFirst().action()
        }

        private data class ScheduledCall(
            val location: WXZCord,
            val action: () -> Unit
        )
    }

    private class ControlledExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        val pendingCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            check(tasks.isNotEmpty()) { "No executor task" }
            tasks.removeFirst().run()
        }
    }

    private companion object {
        val WORLD_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val DIRECT_EXECUTOR = Executor(Runnable::run)
    }
}
