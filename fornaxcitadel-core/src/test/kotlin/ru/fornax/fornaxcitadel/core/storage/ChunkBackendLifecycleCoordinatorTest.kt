package ru.fornax.fornaxcitadel.core.storage

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.OpaqueComponent
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
    fun `saveAll skips clean chunks and saves explicit component edits`() {
        val types = TypeManager()
        val type = types.registerType(INT_KEY, IntComponent::class.java)
        val mapper = ChunkCordSnapshotMapper(types)
        val cleanLocation = location(1, 1)
        val dirtyLocation = location(2, 2)
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(opaqueSnapshot(
                cleanLocation, ComponentSnapshot(INT_KEY, IntComponent(10).toByteArray())
            )))
            enqueueLoad(CompletableFuture.completedFuture(null))
        }
        val storage = createStorage(backend, mapper)
        val clean = storage.loadChunk(cleanLocation).join()
        val dirty = storage.loadChunk(dirtyLocation).join()
        assertFalse(clean.isDirty)
        assertFalse(dirty.isDirty)
        storage.saveAll().join()
        assertTrue(backend.saveCalls.isEmpty())

        dirty.putCitadel(1, 2, 3)
        val writing = CompletableFuture<Void>()
        backend.enqueueSave(writing)
        val saving = storage.saveAll()
        assertEquals(listOf(dirtyLocation), backend.saveCalls.map { it.location })
        assertTrue(dirty.isDirty)
        writing.complete(null)
        saving.join()
        assertFalse(dirty.isDirty)
        storage.saveAll().join()
        assertEquals(1, backend.saveCalls.size)

        val entity = assertNotNull(clean.getCitadel(2, 40, 4))
        assertNotNull(clean.getComponent(entity, type)).value = 20
        clean.markDirty()
        backend.enqueueSave(CompletableFuture.completedFuture(null))
        storage.saveAll().join()
        assertEquals(listOf(dirtyLocation, cleanLocation), backend.saveCalls.map { it.location })
        val saved = mapper.restore(backend.saveCalls.last())
        val savedEntity = assertNotNull(saved.getCitadel(2, 40, 4))
        assertEquals(20, assertNotNull(saved.getComponent(savedEntity, type)).value)
        assertFalse(clean.isDirty)
    }

    @Test
    fun `clean populated chunks unload and close without writes`() {
        for (close in listOf(false, true)) {
            val location = location(3, 3)
            val backend = FakeDBStorage().apply {
                enqueueLoad(CompletableFuture.completedFuture(opaqueSnapshot(
                    location, ComponentSnapshot(UNKNOWN_KEY, byteArrayOf(1))
                )))
            }
            val storage = createStorage(backend)
            val chunk = storage.loadChunk(location).join()
            assertFalse(chunk.isDirty)
            assertFalse(chunk.isEmpty)

            if (close) storage.closeAndSaveAll().join() else storage.unloadChunk(location).join()

            assertTrue(backend.saveCalls.isEmpty())
            assertNull(storage.getIfChunkLoaded(location))
            assertEquals(if (close) 1 else 0, backend.closeCalls)
        }
    }

    @Test
    fun `queued checkpoints wait for a write and skip unchanged contents`() {
        val writing = CompletableFuture<Void>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(null))
            enqueueSave(writing)
        }
        val storage = createStorage(backend)
        val chunk = storage.loadChunk(location(4, 4)).join()
        chunk.putCitadel(1, 2, 3)
        val first = storage.saveAll()
        val second = storage.saveAll()
        assertFalse(first.isDone)
        assertFalse(second.isDone)

        CompletableFuture.runAsync { writing.complete(null) }.join()

        first.join()
        second.join()
        assertEquals(1, backend.saveCalls.size)
        assertFalse(chunk.isDirty)
    }

    @Test
    fun `failed checkpoint stays dirty and retry captures current contents`() {
        val writing = CompletableFuture<Void>()
        val failure = IllegalStateException("write failed")
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(null))
            enqueueSave(writing)
            enqueueSave(CompletableFuture.completedFuture(null))
        }
        val storage = createStorage(backend)
        val chunk = storage.loadChunk(location(5, 5)).join()
        chunk.putCitadel(1, 2, 3)
        val saving = storage.saveAll()
        writing.completeExceptionally(failure)
        assertSame(failure, assertFailsWith<CompletionException> { saving.join() }.cause)
        assertTrue(chunk.isDirty)

        chunk.clear()
        storage.saveAll().join()

        assertEquals(2, backend.saveCalls.size)
        assertEquals(1, backend.saveCalls.first().citadels.size)
        assertTrue(backend.saveCalls.last().citadels.isEmpty())
        assertFalse(chunk.isDirty)
    }

    @Test
    fun `reset followed by a new edit is not cleared by an earlier save`() {
        val writing = CompletableFuture<Void>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(null))
            enqueueSave(writing)
            enqueueSave(CompletableFuture.completedFuture(null))
        }
        val storage = createStorage(backend)
        val chunk = storage.loadChunk(location(6, 6)).join()
        chunk.putCitadel(1, 2, 3)
        val saving = storage.saveAll()
        chunk.resetDirty()
        assertFalse(chunk.isDirty)
        chunk.markDirty()
        writing.complete(null)
        saving.join()
        assertTrue(chunk.isDirty)

        storage.saveAll().join()
        assertEquals(2, backend.saveCalls.size)
        assertFalse(chunk.isDirty)
    }

    @Test
    fun `clean checkpoints skip serialization and snapshot failures remain retryable`() {
        val types = TypeManager()
        val type = types.registerType(INT_KEY, IntComponent::class.java)
        val location = location(7, 7)
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(opaqueSnapshot(
                location, ComponentSnapshot(INT_KEY, IntComponent(10).toByteArray())
            )))
            enqueueSave(CompletableFuture.completedFuture(null))
        }
        val storage = createStorage(backend, ChunkCordSnapshotMapper(types))
        val chunk = storage.loadChunk(location).join()
        val entity = assertNotNull(chunk.getCitadel(2, 40, 4))
        val component = assertNotNull(chunk.getComponent(entity, type))
        component.writeFailure = IllegalStateException("serialization failed")

        storage.saveAll().join()
        assertTrue(backend.saveCalls.isEmpty())
        chunk.markDirty()
        assertFailsWith<CompletionException> { storage.saveAll().join() }
        assertTrue(backend.saveCalls.isEmpty())
        assertTrue(chunk.isDirty)

        component.writeFailure = null
        storage.saveAll().join()
        assertEquals(1, backend.saveCalls.size)
        assertFalse(chunk.isDirty)
    }

    @Test
    fun `discarding corrupted payload during load is persisted by the next save`() {
        val types = TypeManager().apply { registerType(INT_KEY, IntComponent::class.java) }
        val location = location(8, 8)
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(opaqueSnapshot(
                location, ComponentSnapshot(INT_KEY, byteArrayOf(1))
            )))
            enqueueSave(CompletableFuture.completedFuture(null))
        }
        val storage = createStorage(backend, ChunkCordSnapshotMapper(types))
        val chunk = storage.loadChunk(location).join()
        assertTrue(chunk.isDirty)

        storage.saveAll().join()

        assertTrue(backend.saveCalls.single().citadels.single().components.isEmpty())
        assertFalse(chunk.isDirty)
    }

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
        assertEquals(location, loaded.location)
    }

    @Test
    fun `backend snapshot is restored before scheduler publication`() {
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val location = location(8, 11)
        val persisted = ChunkCord(location).apply {
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
        assertEquals(location, save.location)
        assertNotNull(mapper.restore(save).getCitadel(2, 40, 14))
        assertNull(storage.getIfChunkLoaded(location))
        assertFalse(unloading.isDone)

        backendSave.complete(null)

        assertNull(unloading.join())
        assertFalse(loaded.isDirty)
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
        val persisted = ChunkCord(WXZCord(WORLD_ID, -9, 27)).apply {
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
        val location = persisted.location

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
        initiallyLoaded.markDirty()
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
        val persisted = ChunkCord(WXZCord(WORLD_ID, 15, -30)).apply {
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
        val location = persisted.location

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
        initialLoading.join().markDirty()
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
    fun `updateOpaque restores discards and retains components before unload`() {
        val types = TypeManager()
        val mapper = ChunkCordSnapshotMapper(types)
        val location = location(23, -24)
        val unknownPayload = byteArrayOf(9, 7, 5)
        val backend = FakeDBStorage().apply {
            enqueueLoad(
                CompletableFuture.completedFuture(
                    opaqueSnapshot(
                        location,
                        ComponentSnapshot(INT_KEY, IntComponent(71).toByteArray()),
                        ComponentSnapshot(CORRUPT_KEY, byteArrayOf(1, 2)),
                        ComponentSnapshot(UNKNOWN_KEY, unknownPayload)
                    )
                )
            )
            enqueueSave(CompletableFuture.completedFuture(null))
        }
        val storage = createStorage(backend, mapper)
        val chunk = storage.loadChunk(location).join()
        val entity = assertNotNull(chunk.getCitadel(2, 40, 4))
        val intType = types.registerType(INT_KEY, IntComponent::class.java)
        types.registerType(CORRUPT_KEY, CorruptibleComponent::class.java)

        val result = storage.updateOpaque(chunk).join()

        assertEquals(OpaqueUpdateResult(1, 1, 1, 0), result)
        assertSame(entity, chunk.getCitadel(2, 40, 4))
        assertEquals(71, assertNotNull(chunk.getComponent(entity, intType)).value)
        assertEquals(listOf(UNKNOWN_KEY), chunk.captureOpaqueComponents().map { it.key })

        storage.unloadChunk(location).join()

        val savedComponents = backend.saveCalls.single().citadels.single().components
        assertEquals(listOf(INT_KEY, UNKNOWN_KEY), savedComponents.map { it.key })
        assertContentEquals(
            unknownPayload,
            savedComponents.single { it.key == UNKNOWN_KEY }.payload
        )
    }

    @Test
    fun `key update scans loaded chunks and counts only matching chunks`() {
        val types = TypeManager()
        val mapper = ChunkCordSnapshotMapper(types)
        val intLocation = location(51, 52)
        val unknownLocation = location(61, 62)
        val emptyLocation = location(71, 72)
        val backend = FakeDBStorage().apply {
            enqueueLoad(
                CompletableFuture.completedFuture(
                    opaqueSnapshot(
                        intLocation,
                        ComponentSnapshot(INT_KEY, IntComponent(19).toByteArray())
                    )
                )
            )
            enqueueLoad(
                CompletableFuture.completedFuture(
                    opaqueSnapshot(
                        unknownLocation,
                        ComponentSnapshot(UNKNOWN_KEY, byteArrayOf(4, 5, 6))
                    )
                )
            )
            enqueueLoad(
                CompletableFuture.completedFuture(
                    ChunkCordSnapshot(emptyLocation)
                )
            )
        }
        val storage = createStorage(backend, mapper)
        val intChunk = storage.loadChunk(intLocation).join()
        val unknownChunk = storage.loadChunk(unknownLocation).join()
        storage.loadChunk(emptyLocation).join()
        val intEntity = assertNotNull(intChunk.getCitadel(2, 40, 4))
        val unknownEntity = assertNotNull(unknownChunk.getCitadel(2, 40, 4))
        val intType = types.registerType(INT_KEY, IntComponent::class.java)

        val first = storage.updateOpaque(INT_KEY).join()

        assertEquals(1, first.chunksFound)
        assertEquals(1, first.chunksProcessed)
        assertEquals(0, first.chunksFailed)
        assertEquals(OpaqueUpdateResult(restored = 1), first.components)
        assertTrue(first.failures.isEmpty())
        assertEquals(19, assertNotNull(intChunk.getComponent(intEntity, intType)).value)
        assertEquals(listOf(UNKNOWN_KEY), unknownChunk.captureOpaqueComponents().map { it.key })

        val repeated = storage.updateOpaque(INT_KEY).join()

        assertEquals(0, repeated.chunksFound)
        assertEquals(OpaqueUpdateResult(), repeated.components)

        val unresolved = storage.updateOpaque(UNKNOWN_KEY).join()

        assertEquals(1, unresolved.chunksFound)
        assertEquals(1, unresolved.chunksProcessed)
        assertEquals(OpaqueUpdateResult(unresolved = 1), unresolved.components)

        val unknownType = types.registerType(UNKNOWN_KEY, UnknownComponent::class.java)
        assertNull(unknownChunk.removeComponent(unknownEntity, unknownType))

        assertEquals(0, storage.updateOpaque(UNKNOWN_KEY).join().chunksFound)
    }

    @Test
    fun `key scan captures current components on owner scheduler and skips empty chunks`() {
        val types = TypeManager()
        val originalLocation = location(81, 82)
        val addedLocation = location(91, 92)
        val backend = FakeDBStorage().apply {
            enqueueLoad(
                CompletableFuture.completedFuture(
                    opaqueSnapshot(
                        originalLocation,
                        ComponentSnapshot(INT_KEY, IntComponent(19).toByteArray())
                    )
                )
            )
            enqueueLoad(CompletableFuture.completedFuture(ChunkCordSnapshot(addedLocation)))
        }
        val scheduler = ControlledScheduler()
        val codecExecutor = ControlledExecutor()
        val storage = ChunkBackendLifecycleCoordinator(
            backend, scheduler, ChunkCordSnapshotMapper(types), codecExecutor
        )
        val originalLoading = storage.loadChunk(originalLocation)
        codecExecutor.runNext()
        scheduler.runNext()
        val original = originalLoading.join()
        val addedLoading = storage.loadChunk(addedLocation)
        codecExecutor.runNext()
        scheduler.runNext()
        val added = addedLoading.join()
        val type = types.registerType(INT_KEY, IntComponent::class.java)

        val updating = storage.updateOpaque(INT_KEY)

        assertEquals(2, scheduler.pendingCount)
        assertEquals(0, codecExecutor.pendingCount)
        assertFalse(updating.isDone)
        val originalEntity = assertNotNull(original.getCitadel(2, 40, 4))
        assertNull(original.removeComponent(originalEntity, type))
        val addedEntity = added.putCitadel(3, 41, 5)
        added.putOpaqueComponent(addedEntity, OpaqueComponent(INT_KEY, IntComponent(29).toByteArray()))

        repeat(2) { scheduler.runNext() }

        assertEquals(0, scheduler.pendingCount)
        assertEquals(1, codecExecutor.pendingCount)
        assertFalse(updating.isDone)
        codecExecutor.runNext()
        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()

        val result = updating.join()
        assertEquals(1, result.chunksFound)
        assertEquals(1, result.chunksProcessed)
        assertEquals(0, result.chunksFailed)
        assertEquals(OpaqueUpdateResult(restored = 1), result.components)
        assertNull(original.getComponent(originalEntity, type))
        assertEquals(29, assertNotNull(added.getComponent(addedEntity, type)).value)
    }

    @Test
    fun `key scan finishes before a queued unload or close`() {
        for (close in listOf(false, true)) {
            val types = TypeManager()
            val location = location(101, 102)
            val backend = FakeDBStorage().apply {
                enqueueLoad(
                    CompletableFuture.completedFuture(
                        opaqueSnapshot(
                            location,
                            ComponentSnapshot(INT_KEY, IntComponent(37).toByteArray())
                        )
                    )
                )
                enqueueSave(CompletableFuture.completedFuture(null))
            }
            val scheduler = ControlledScheduler()
            val codecExecutor = ControlledExecutor()
            val storage = ChunkBackendLifecycleCoordinator(
                backend, scheduler, ChunkCordSnapshotMapper(types), codecExecutor
            )
            val loading = storage.loadChunk(location)
            codecExecutor.runNext()
            scheduler.runNext()
            loading.join()
            types.registerType(INT_KEY, IntComponent::class.java)

            val updating = storage.updateOpaque(INT_KEY)
            val finishing = if (close) storage.closeAndSaveAll() else storage.unloadChunk(location)

            assertEquals(1, scheduler.pendingCount)
            scheduler.runNext()
            assertFalse(updating.isDone)
            assertFalse(finishing.isDone)
            assertTrue(backend.saveCalls.isEmpty())
            codecExecutor.runNext()
            scheduler.runNext()

            assertEquals(OpaqueUpdateResult(restored = 1), updating.join().components)
            assertFalse(finishing.isDone)
            assertTrue(backend.saveCalls.isEmpty())
            assertEquals(1, scheduler.pendingCount)
            scheduler.runNext()

            assertNull(finishing.join())
            assertNull(storage.getIfChunkLoaded(location))
            assertEquals(1, backend.saveCalls.size)
            assertEquals(if (close) 1 else 0, backend.closeCalls)
        }
    }

    @Test
    fun `key scan reports failures without stopping other chunks and can be retried`() {
        val types = TypeManager()
        val failedLocation = location(111, 112)
        val healthyLocation = location(121, 122)
        val backend = FakeDBStorage().apply {
            for (location in listOf(failedLocation, healthyLocation)) {
                enqueueLoad(
                    CompletableFuture.completedFuture(
                        opaqueSnapshot(
                            location,
                            ComponentSnapshot(INT_KEY, IntComponent(41).toByteArray())
                        )
                    )
                )
            }
        }
        var rejectScan = false
        val failure = IllegalStateException("Owner scheduler unavailable")
        val scheduler = object : Scheduler {
            override fun <T> schedule(location: WXZCord, task: Callable<T>): CompletableFuture<T> {
                return if (rejectScan && location == failedLocation) {
                    CompletableFuture.failedFuture(failure)
                } else {
                    DirectScheduler.schedule(location, task)
                }
            }
        }
        val storage = ChunkBackendLifecycleCoordinator(
            backend, scheduler, ChunkCordSnapshotMapper(types), DIRECT_EXECUTOR
        )
        val failedChunk = storage.loadChunk(failedLocation).join()
        val healthyChunk = storage.loadChunk(healthyLocation).join()
        val type = types.registerType(INT_KEY, IntComponent::class.java)
        rejectScan = true

        val result = storage.updateOpaque(INT_KEY).join()

        assertEquals(2, result.chunksFound)
        assertEquals(1, result.chunksProcessed)
        assertEquals(1, result.chunksFailed)
        assertEquals(OpaqueUpdateResult(restored = 1), result.components)
        assertEquals(mapOf(failedLocation to failure), result.failures)
        assertEquals(1, failedChunk.captureOpaqueComponents(INT_KEY).size)
        val healthyEntity = assertNotNull(healthyChunk.getCitadel(2, 40, 4))
        assertEquals(41, assertNotNull(healthyChunk.getComponent(healthyEntity, type)).value)

        rejectScan = false
        val retry = storage.updateOpaque(INT_KEY).join()

        assertEquals(1, retry.chunksFound)
        assertEquals(1, retry.chunksProcessed)
        assertEquals(0, retry.chunksFailed)
        assertEquals(OpaqueUpdateResult(restored = 1), retry.components)
        assertTrue(retry.failures.isEmpty())
        val failedEntity = assertNotNull(failedChunk.getCitadel(2, 40, 4))
        assertEquals(41, assertNotNull(failedChunk.getComponent(failedEntity, type)).value)
    }

    @Test
    fun `opaque update uses compare and set and finishes before queued unload`() {
        val types = TypeManager()
        val mapper = ChunkCordSnapshotMapper(types)
        val location = location(-51, -52)
        val backend = FakeDBStorage().apply {
            enqueueLoad(
                CompletableFuture.completedFuture(
                    opaqueSnapshot(
                        location,
                        ComponentSnapshot(INT_KEY, IntComponent(37).toByteArray())
                    )
                )
            )
            enqueueSave(CompletableFuture.completedFuture(null))
        }
        val codecExecutor = ControlledExecutor()
        val storage = ChunkBackendLifecycleCoordinator(
            backend,
            DirectScheduler,
            mapper,
            codecExecutor
        )
        val loading = storage.loadChunk(location)
        codecExecutor.runNext()
        val chunk = loading.join()
        val entity = assertNotNull(chunk.getCitadel(2, 40, 4))
        val intType = types.registerType(INT_KEY, IntComponent::class.java)

        val updating = storage.updateOpaque(chunk)
        val unloading = storage.unloadChunk(location)

        assertEquals(1, codecExecutor.pendingCount)
        assertFalse(updating.isDone)
        assertFalse(unloading.isDone)
        assertTrue(backend.saveCalls.isEmpty())

        assertNull(chunk.removeComponent(entity, intType))
        codecExecutor.runNext()

        assertEquals(OpaqueUpdateResult(skipped = 1), updating.join())
        assertNull(unloading.join())
        assertEquals(1, backend.saveCalls.size)
        assertTrue(backend.saveCalls.single().citadels.single().components.isEmpty())
        assertEquals(0, storage.updateOpaque(INT_KEY).join().chunksFound)
    }

    @Test
    fun `opaque update rejects stale instances and chunks with an accepted unload`() {
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val location = location(-63, 64)
        val backend = FakeDBStorage().apply {
            enqueueLoad(
                CompletableFuture.completedFuture(
                    opaqueSnapshot(
                        location,
                        ComponentSnapshot(INT_KEY, IntComponent(12).toByteArray())
                    )
                )
            )
            enqueueSave(CompletableFuture.completedFuture(null))
        }
        val scheduler = ControlledScheduler()
        val storage = ChunkBackendLifecycleCoordinator(
            backend,
            scheduler,
            mapper,
            DIRECT_EXECUTOR
        )
        val loading = storage.loadChunk(location)
        scheduler.runNext()
        val loaded = loading.join()

        assertRejected {
            storage.updateOpaque(ChunkCord(location))
        }

        val unloading = storage.unloadChunk(location)

        assertRejected { storage.updateOpaque(loaded) }
        val skipped = storage.updateOpaque(INT_KEY).join()
        assertEquals(0, skipped.chunksFound)
        assertEquals(0, skipped.chunksFailed)
        assertEquals(1, scheduler.pendingCount)
        assertSame(loaded, storage.getIfChunkLoaded(location))
        assertFalse(unloading.isDone)

        scheduler.runNext()

        assertNull(unloading.join())
        assertNull(storage.getIfChunkLoaded(location))
        assertEquals(0, storage.updateOpaque(INT_KEY).join().chunksFound)
    }

    @Test
    fun `saveAll checkpoints a load accepted before the barrier`() {
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val location = location(18, -33)
        val persisted = ChunkCord(location).apply {
            putCitadel(3, 91, 7)
        }
        val backendLoad = CompletableFuture<ChunkCordSnapshot?>()
        val backendSave = CompletableFuture<Void>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(backendLoad)
            enqueueSave(backendSave)
        }
        val scheduler = ControlledScheduler()
        val storage = ChunkBackendLifecycleCoordinator(backend, scheduler, mapper, DIRECT_EXECUTOR)

        val loading = storage.loadChunk(location)
        val saving = storage.saveAll()

        assertFalse(loading.isDone)
        assertFalse(saving.isDone)
        assertTrue(backend.saveCalls.isEmpty())

        backendLoad.complete(mapper.snapshot(persisted))

        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()

        assertNotNull(loading.join().getCitadel(3, 91, 7))
        loading.join().markDirty()
        assertEquals(1, scheduler.pendingCount)
        assertFalse(saving.isDone)
        assertTrue(backend.saveCalls.isEmpty())

        scheduler.runNext()

        assertEquals(1, backend.saveCalls.size)
        assertNotNull(mapper.restore(backend.saveCalls.single()).getCitadel(3, 91, 7))
        assertFalse(saving.isDone)

        backendSave.complete(null)

        assertNull(saving.join())
    }

    @Test
    fun `close drains accepted load rejects new operations and closes backend`() {
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val location = location(-22, 45)
        val persisted = ChunkCord(location).apply {
            putCitadel(8, 64, 8)
        }
        val backendLoad = CompletableFuture<ChunkCordSnapshot?>()
        val backendSave = CompletableFuture<Void>()
        val backendClose = CompletableFuture<Void>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(backendLoad)
            enqueueSave(backendSave)
            enqueueClose(backendClose)
        }
        val scheduler = ControlledScheduler()
        val storage = ChunkBackendLifecycleCoordinator(backend, scheduler, mapper, DIRECT_EXECUTOR)

        val loading = storage.loadChunk(location)
        val closing = storage.closeAndSaveAll()
        val concurrentClosing = storage.closeAndSaveAll()

        assertRejected { storage.loadChunk(location(90, 90)) }
        assertRejected { storage.unloadChunk(location) }
        assertRejected { storage.updateOpaque(ChunkCord(location)) }
        assertRejected { storage.updateOpaque(INT_KEY) }
        assertRejected { storage.saveAll() }
        assertFalse(closing.isDone)
        assertFalse(concurrentClosing.isDone)

        backendLoad.complete(mapper.snapshot(persisted))
        scheduler.runNext()

        assertNotNull(loading.join().getCitadel(8, 64, 8))
        loading.join().markDirty()
        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()

        assertEquals(1, backend.saveCalls.size)
        assertEquals(0, backend.closeCalls)
        backendSave.complete(null)

        assertEquals(1, backend.closeCalls)
        assertFalse(closing.isDone)
        backendClose.complete(null)

        assertNull(closing.join())
        assertNull(concurrentClosing.join())
        assertNull(storage.getIfChunkLoaded(location))
        assertEquals(1, backend.closeCalls)
        assertRejected { storage.closeAndSaveAll().thenCompose { storage.saveAll() } }
    }

    @Test
    fun `close completes after an accepted load future with direct execution`() {
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val location = location(-23, 46)
        val backendLoad = CompletableFuture<ChunkCordSnapshot?>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(backendLoad)
            enqueueSave(CompletableFuture.completedFuture(null))
        }
        val storage = createStorage(backend, mapper)

        val loading = storage.loadChunk(location)
        val closing = storage.closeAndSaveAll()

        backendLoad.complete(
            ChunkCordSnapshot(location)
        )

        assertTrue(loading.isDone)
        assertTrue(closing.isDone)
        assertNull(closing.join())
    }

    @Test
    fun `large synchronous lifecycle queue drains without recursion`() {
        val location = location(-24, 47)
        val initialLoad = CompletableFuture<ChunkCordSnapshot?>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(initialLoad)
            repeat(2048) {
                enqueueLoad(CompletableFuture.completedFuture(null))
            }
        }
        val storage = createStorage(backend)
        storage.loadChunk(location)
        var finalLoad: CompletableFuture<ChunkCord>? = null

        repeat(2048) {
            storage.unloadChunk(location)
            finalLoad = storage.loadChunk(location)
        }

        initialLoad.complete(null)

        assertNotNull(finalLoad).join()
        assertEquals(2049, backend.loadCalls.size)
        assertTrue(backend.saveCalls.isEmpty())
    }

    @Test
    fun `failed operation advances slot and retries retained unload before reload`() {
        val failure = IllegalStateException("write failed")
        val failedSave = CompletableFuture<Void>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(null))
            enqueueLoad(CompletableFuture.completedFuture(null))
            enqueueSave(failedSave)
            enqueueSave(CompletableFuture.completedFuture(null))
        }
        val storage = createStorage(backend)
        val location = location(-25, 48)
        val original = storage.loadChunk(location).join().apply {
            putCitadel(2, 71, 5)
        }

        val unloading = storage.unloadChunk(location)
        val reloading = storage.loadChunk(location)

        failedSave.completeExceptionally(failure)

        val exception = assertFailsWith<CompletionException> { unloading.join() }
        assertSame(failure, exception.cause)
        val reloaded = reloading.join()
        assertNotSame(original, reloaded)
        assertSame(reloaded, storage.getIfChunkLoaded(location))
        assertEquals(2, backend.saveCalls.size)
        assertSame(backend.saveCalls[0], backend.saveCalls[1])
        assertEquals(2, backend.loadCalls.size)
    }

    @Test
    fun `caller cancellation does not cancel shared load or queued tail`() {
        val backendLoad = CompletableFuture<ChunkCordSnapshot?>()
        val backendSave = CompletableFuture<Void>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(backendLoad)
            enqueueLoad(CompletableFuture.completedFuture(null))
            enqueueSave(backendSave)
        }
        val storage = createStorage(backend)
        val location = location(-26, 49)

        val cancelledLoad = storage.loadChunk(location)
        val sharedLoad = storage.loadChunk(location)

        assertTrue(cancelledLoad.cancel(true))
        backendLoad.complete(null)

        val original = sharedLoad.join()
        original.markDirty()
        assertTrue(cancelledLoad.isCancelled)
        assertSame(original, storage.getIfChunkLoaded(location))
        assertEquals(1, backend.loadCalls.size)

        val cancelledUnload = storage.unloadChunk(location)
        val reloading = storage.loadChunk(location)

        assertTrue(cancelledUnload.cancel(true))
        backendSave.complete(null)

        val reloaded = reloading.join()
        assertTrue(cancelledUnload.isCancelled)
        assertNotSame(original, reloaded)
        assertSame(reloaded, storage.getIfChunkLoaded(location))
        assertEquals(2, backend.loadCalls.size)
        assertEquals(1, backend.saveCalls.size)
    }

    @Test
    fun `saveAll and close wait for accepted opaque updates`() {
        val saveTypes = TypeManager()
        val saveMapper = ChunkCordSnapshotMapper(saveTypes)
        val saveLocation = location(-27, 50)
        val saveWrite = CompletableFuture<Void>()
        val saveBackend = FakeDBStorage().apply {
            enqueueLoad(
                CompletableFuture.completedFuture(
                    opaqueSnapshot(
                        saveLocation,
                        ComponentSnapshot(INT_KEY, IntComponent(81).toByteArray())
                    )
                )
            )
            enqueueSave(saveWrite)
        }
        val saveCodec = ControlledExecutor()
        val saveStorage = ChunkBackendLifecycleCoordinator(
            saveBackend,
            DirectScheduler,
            saveMapper,
            saveCodec
        )
        val saveLoading = saveStorage.loadChunk(saveLocation)
        saveCodec.runNext()
        val saveChunk = saveLoading.join()
        val saveType = saveTypes.registerType(INT_KEY, IntComponent::class.java)

        val saveUpdating = saveStorage.updateOpaque(saveChunk)
        val saving = saveStorage.saveAll()

        assertFalse(saveUpdating.isDone)
        assertFalse(saving.isDone)
        assertTrue(saveBackend.saveCalls.isEmpty())
        saveCodec.runNext()

        assertEquals(OpaqueUpdateResult(restored = 1), saveUpdating.join())
        assertEquals(1, saveBackend.saveCalls.size)
        val savedChunk = saveMapper.restore(saveBackend.saveCalls.single())
        val savedEntity = assertNotNull(savedChunk.getCitadel(2, 40, 4))
        assertEquals(81, assertNotNull(savedChunk.getComponent(savedEntity, saveType)).value)
        assertFalse(saving.isDone)

        saveWrite.complete(null)
        assertNull(saving.join())

        val closeTypes = TypeManager()
        val closeMapper = ChunkCordSnapshotMapper(closeTypes)
        val closeLocation = location(-28, 51)
        val closeWrite = CompletableFuture<Void>()
        val closeBackend = FakeDBStorage().apply {
            enqueueLoad(
                CompletableFuture.completedFuture(
                    opaqueSnapshot(
                        closeLocation,
                        ComponentSnapshot(INT_KEY, IntComponent(82).toByteArray())
                    )
                )
            )
            enqueueSave(closeWrite)
        }
        val closeCodec = ControlledExecutor()
        val closeStorage = ChunkBackendLifecycleCoordinator(
            closeBackend,
            DirectScheduler,
            closeMapper,
            closeCodec
        )
        val closeLoading = closeStorage.loadChunk(closeLocation)
        closeCodec.runNext()
        val closeChunk = closeLoading.join()
        val closeType = closeTypes.registerType(INT_KEY, IntComponent::class.java)

        val closeUpdating = closeStorage.updateOpaque(closeChunk)
        val closing = closeStorage.closeAndSaveAll()

        assertFalse(closeUpdating.isDone)
        assertFalse(closing.isDone)
        assertTrue(closeBackend.saveCalls.isEmpty())
        assertEquals(0, closeBackend.closeCalls)
        closeCodec.runNext()

        assertEquals(OpaqueUpdateResult(restored = 1), closeUpdating.join())
        assertEquals(1, closeBackend.saveCalls.size)
        val closedChunk = closeMapper.restore(closeBackend.saveCalls.single())
        val closedEntity = assertNotNull(closedChunk.getCitadel(2, 40, 4))
        assertEquals(82, assertNotNull(closedChunk.getComponent(closedEntity, closeType)).value)
        assertFalse(closing.isDone)
        assertEquals(0, closeBackend.closeCalls)

        closeWrite.complete(null)
        assertNull(closing.join())
        assertEquals(1, closeBackend.closeCalls)
    }

    @Test
    fun `failed backend close is shared by current callers and can be retried`() {
        val failure = IllegalStateException("close failed")
        val backendClose = CompletableFuture<Void>()
        val backend = FakeDBStorage().apply {
            enqueueClose(backendClose)
        }
        val storage = createStorage(backend)

        val first = storage.closeAndSaveAll()
        val concurrent = storage.closeAndSaveAll()

        assertEquals(1, backend.closeCalls)
        assertFalse(first.isDone)
        assertFalse(concurrent.isDone)
        backendClose.completeExceptionally(failure)

        val firstFailure = assertFailsWith<CompletionException> { first.join() }
        val concurrentFailure = assertFailsWith<CompletionException> { concurrent.join() }
        assertSame(failure, firstFailure.cause)
        assertSame(failure, concurrentFailure.cause)

        assertNull(storage.closeAndSaveAll().join())
        assertEquals(2, backend.closeCalls)
        assertRejected { storage.saveAll() }
    }

    @Test
    fun `failed final save is retained and retried by close`() {
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val failure = IllegalStateException("save failed")
        val failedSave = CompletableFuture<Void>().apply {
            completeExceptionally(failure)
        }
        val retrySave = CompletableFuture<Void>()
        val backend = FakeDBStorage().apply {
            enqueueLoad(CompletableFuture.completedFuture(null))
            enqueueSave(failedSave)
            enqueueSave(retrySave)
        }
        val storage = createStorage(backend, mapper)
        val location = location(13, -22)
        storage.loadChunk(location).join().putCitadel(7, 101, 4)

        val first = storage.closeAndSaveAll()
        val firstFailure = assertFailsWith<CompletionException> { first.join() }
        assertSame(failure, firstFailure.cause)
        assertEquals(1, backend.saveCalls.size)
        assertEquals(0, backend.closeCalls)

        val retry = storage.closeAndSaveAll()
        assertEquals(2, backend.saveCalls.size)
        assertSame(backend.saveCalls[0], backend.saveCalls[1])
        assertFalse(retry.isDone)

        retrySave.complete(null)

        assertNull(retry.join())
        assertEquals(1, backend.closeCalls)
        assertRejected { storage.loadChunk(location) }
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
        val chunk = storage.loadChunk(location).join()
        chunk.putCitadel(7, 101, 4)

        val unloading = storage.unloadChunk(location)

        val exception = assertFailsWith<CompletionException> { unloading.join() }
        assertSame(firstFailure, exception.cause)
        assertTrue(chunk.isDirty)
        assertNull(storage.getIfChunkLoaded(location))
        assertEquals(1, backend.saveCalls.size)

        val saving = storage.saveAll()

        assertEquals(2, backend.saveCalls.size)
        assertEquals(location, backend.saveCalls[1].location)
        assertSame(backend.saveCalls[0], backend.saveCalls[1])
        assertFalse(saving.isDone)

        retrySave.complete(null)

        assertNull(saving.join())
        assertFalse(chunk.isDirty)
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
        assertTrue(loaded.isDirty)
        assertEquals(2, backend.saveCalls.size)
        assertEquals(1, backend.saveCalls[0].citadels.size)
        assertEquals(2, backend.saveCalls[1].citadels.size)
        assertFalse(secondSave.isDone)

        secondBackendSave.complete(null)

        assertNull(secondSave.join())
        assertFalse(loaded.isDirty)
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
            backend.saveCalls.map { it.location }
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

    private fun opaqueSnapshot(
        location: WXZCord,
        vararg components: ComponentSnapshot
    ): ChunkCordSnapshot {
        return ChunkCordSnapshot(
            location,
            listOf(
                CitadelSnapshot(
                    ChunkCord.packPosition(2, 40, 4),
                    components.toList()
                )
            )
        )
    }

    private fun assertRejected(operation: () -> CompletableFuture<*>) {
        val exception = assertFailsWith<CompletionException> {
            operation().join()
        }
        assertTrue(exception.cause is IllegalStateException)
    }

    private class FakeDBStorage : DBStorage {
        private val loads = ArrayDeque<CompletableFuture<ChunkCordSnapshot?>>()
        private val saves = ArrayDeque<CompletableFuture<Void>>()

        val loadCalls = ArrayList<WXZCord>()
        val saveCalls = ArrayList<ChunkCordSnapshot>()
        private val closes = ArrayDeque<CompletableFuture<Void>>()

        var closeCalls: Int = 0
            private set

        fun enqueueLoad(result: CompletableFuture<ChunkCordSnapshot?>) {
            loads.addLast(result)
        }

        fun enqueueSave(result: CompletableFuture<Void>) {
            saves.addLast(result)
        }

        fun enqueueClose(result: CompletableFuture<Void>) {
            closes.addLast(result)
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

        override fun close(): CompletableFuture<Void> {
            closeCalls++
            return if (closes.isEmpty()) {
                CompletableFuture.completedFuture(null)
            } else {
                closes.removeFirst()
            }
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

    private class IntComponent(
        var value: Int = 0
    ) : Component() {
        var writeFailure: RuntimeException? = null

        override fun write(output: DataOutput) {
            writeFailure?.let { throw it }
            output.writeInt(value)
        }

        override fun read(input: DataInput) {
            value = input.readInt()
        }
    }

    private class CorruptibleComponent(
        var value: Int = 0
    ) : Component() {
        override fun write(output: DataOutput) {
            output.writeInt(value)
        }

        override fun read(input: DataInput) {
            value = input.readInt()
        }
    }

    private class UnknownComponent : Component() {
        override fun write(output: DataOutput) = Unit

        override fun read(input: DataInput) = Unit
    }

    private companion object {
        val WORLD_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val DIRECT_EXECUTOR = Executor(Runnable::run)
        val INT_KEY = Key("test", "int")
        val CORRUPT_KEY = Key("test", "corrupt")
        val UNKNOWN_KEY = Key("test", "unknown")
    }
}
