package ru.fornax.fornaxcitadel.paper

import org.bukkit.NamespacedKey
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.breaking.DamageBreakPolicy
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.SimpleHealthBehavior
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.storage.PaperChunkListener
import ru.fornax.fornaxcitadel.paper.storage.PersistContainerChunkStorage
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
import kotlin.test.assertTrue

class PaperChunkPersistenceTest {
    @Test
    fun `world unload writes pending PDC and detaches all chunks before world disappearance`() {
        val fixture = Fixture()
        fixture.listener.loadAlreadyLoadedChunks()
        assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(PlaceAction(
            fixture.paper.context(fixture.paper.installer), fixture.paper.position, PaperCitadelTypes.REINFORCED
        )))
        val event = WorldUnloadEvent(fixture.paper.world)

        dispatch(fixture.listener, event, fixture.paper.plugin)

        assertFalse(event.isCancelled)
        assertFalse(fixture.manager.isChunkLoaded(fixture.paper.worldId, 0, 0))
        assertTrue(fixture.backend.registeredChunks(fixture.paper.worldId).isEmpty())
        assertEquals(1, fixture.paper.worldSaveCount)
        fixture.paper.worldAvailable = false
        fixture.paper.worldChunkLoaded = false
        fixture.paper.runScheduledTasks()
        assertFalse(fixture.manager.isChunkLoaded(fixture.paper.worldId, 0, 0))
    }

    @Test
    fun `late cancellation of world unload restores core from the saved PDC`() {
        val fixture = Fixture()
        fixture.listener.loadAlreadyLoadedChunks()
        assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(PlaceAction(
            fixture.paper.context(fixture.paper.installer), fixture.paper.position, PaperCitadelTypes.REINFORCED
        )))
        val event = WorldUnloadEvent(fixture.paper.world)
        dispatch(fixture.listener, event, fixture.paper.plugin)
        event.isCancelled = true
        fixture.paper.freshChunkWrappers = true

        fixture.paper.runScheduledTasks()

        val restored = assertNotNull(fixture.manager.findCitadel(fixture.paper.worldId, 1, 64, 2))
        assertEquals(PaperCitadelTypes.REINFORCED, restored.getComponent(CitadelKind::class.java)?.definitionKey)
        assertEquals(100.0, restored.getComponent(Health::class.java)?.health)
        assertEquals(1, fixture.backend.registeredChunks(fixture.paper.worldId).size)
    }

    @Test
    fun `world unload without core changes does not force a world save`() {
        val fixture = Fixture()
        fixture.listener.loadAlreadyLoadedChunks()

        dispatch(fixture.listener, WorldUnloadEvent(fixture.paper.world), fixture.paper.plugin)

        assertEquals(0, fixture.paper.worldSaveCount)
    }

    @Test
    fun `dirty unload synchronously writes PDC before unregister and reload restores type and damage`() {
        val fixture = Fixture()
        fixture.listener.loadAlreadyLoadedChunks()
        assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(PlaceAction(
            fixture.paper.context(fixture.paper.installer), fixture.paper.position, PaperCitadelTypes.REINFORCED
        )))
        assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(DamageAction(
            fixture.paper.context(fixture.paper.stranger), fixture.paper.position, 25.0
        )))
        val oldHandle = assertNotNull(fixture.manager.findCitadel(fixture.paper.worldId, 1, 64, 2))
        val unload = ChunkUnloadEvent(fixture.paper.chunk, false)

        fixture.listener.onUnload(unload)

        assertTrue(unload.isSaveChunk)
        assertFalse(fixture.manager.isChunkLoaded(fixture.paper.worldId, 0, 0))
        assertFalse(oldHandle.isCurrent)
        assertTrue(fixture.paper.chunkData.values.values.single() is ByteArray)
        assertFailsWith<CompletionException> { fixture.backend.loadChunk(fixture.location).join() }

        fixture.listener.onLoad(ChunkLoadEvent(fixture.paper.chunk, false))

        val restored = assertNotNull(fixture.manager.findCitadel(fixture.paper.worldId, 1, 64, 2))
        assertEquals(PaperCitadelTypes.REINFORCED, restored.getComponent(CitadelKind::class.java)?.definitionKey)
        assertEquals(75.0, restored.getComponent(Health::class.java)?.health)
        assertFalse(fixture.backend.hasPendingChunkSave(fixture.paper.chunk))
    }

    @Test
    fun `clean empty chunk unload leaves another callers no-save choice unchanged`() {
        val fixture = Fixture()
        fixture.listener.onLoad(ChunkLoadEvent(fixture.paper.chunk, false))
        val unload = ChunkUnloadEvent(fixture.paper.chunk, false)

        fixture.listener.onUnload(unload)

        assertFalse(unload.isSaveChunk)
        assertTrue(fixture.paper.chunkData.values.isEmpty())
    }

    @Test
    fun `corrupt PDC fails closed and remains untouched through shutdown`() {
        val fixture = Fixture()
        val corrupt = byteArrayOf(1, 2, 3)
        fixture.paper.chunkData.values[fixture.key] = corrupt

        fixture.listener.onLoad(ChunkLoadEvent(fixture.paper.chunk, false))

        assertFalse(fixture.manager.isChunkLoaded(fixture.paper.worldId, 0, 0))
        fixture.listener.closeAndSaveAll()
        assertContentEquals(corrupt, fixture.paper.chunkData.values[fixture.key] as ByteArray)
    }

    @Test
    fun `PDC for different chunk coordinates is not accepted as this chunk`() {
        val fixture = Fixture()
        val wrongLocation = WXZCord(fixture.paper.worldId, 1, 0)
        val original = ChunkCordSnapshot(wrongLocation, emptyList()).toByteArray()
        fixture.paper.chunkData.values[fixture.key] = original
        fixture.backend.registerChunk(fixture.paper.chunk)

        assertFailsWith<CompletionException> { fixture.backend.loadChunk(fixture.location).join() }
        assertContentEquals(original, fixture.paper.chunkData.values[fixture.key] as ByteArray)
    }

    @Test
    fun `shutdown writes dirty state inline and releases backend chunk references`() {
        val fixture = Fixture()
        fixture.listener.loadAlreadyLoadedChunks()
        assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(PlaceAction(
            fixture.paper.context(fixture.paper.installer), fixture.paper.position, PaperCitadelTypes.REINFORCED
        )))
        fixture.paper.enabled = false

        fixture.listener.closeAndSaveAll()

        val snapshot = ChunkCordSnapshot.fromByteArray(fixture.paper.chunkData.values[fixture.key] as ByteArray)
        assertEquals(1, snapshot.citadels.size)
        assertFailsWith<CompletionException> { fixture.backend.loadChunk(fixture.location).join() }
    }

    @Test
    fun `lifecycle rejects incomplete save future instead of blocking the server thread`() {
        val pendingScheduler = object : Scheduler {
            override fun <T> schedule(location: WXZCord, task: Callable<T>) = CompletableFuture<T>()
        }
        val fixture = Fixture(pendingScheduler)
        fixture.listener.onLoad(ChunkLoadEvent(fixture.paper.chunk, false))

        assertFailsWith<IllegalStateException> { fixture.listener.saveAll() }
        assertFalse(fixture.manager.isChunkLoaded(fixture.paper.worldId, 0, 0))
    }

    @Test
    fun `backend rejects off-thread registry and PDC access`() {
        val fixture = Fixture()
        fixture.backend.registerChunk(fixture.paper.chunk)
        fixture.paper.mainThread = false

        assertFailsWith<IllegalStateException> { fixture.backend.unregisterChunk(fixture.paper.chunk) }
        assertFailsWith<CompletionException> { fixture.backend.loadChunk(fixture.location).join() }
        assertFailsWith<CompletionException> {
            fixture.backend.saveChunk(ChunkCordSnapshot(fixture.location, emptyList())).join()
        }
    }

    @Test
    fun `different Bukkit wrappers share pending save status and unload registration`() {
        val fixture = Fixture()
        fixture.listener.loadAlreadyLoadedChunks()
        assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(PlaceAction(
            fixture.paper.context(fixture.paper.installer), fixture.paper.position, PaperCitadelTypes.REINFORCED
        )))
        fixture.listener.saveAll()
        val newWrapper = fixture.paper.chunkWrapper()

        fixture.backend.registerChunk(newWrapper)

        assertTrue(fixture.backend.hasPendingChunkSave(newWrapper))
        assertTrue(fixture.backend.hasPendingChunkSave(fixture.paper.chunk))
        val unload = ChunkUnloadEvent(fixture.paper.chunkWrapper(), false)
        fixture.listener.onUnload(unload)
        assertTrue(unload.isSaveChunk)
        assertTrue(fixture.backend.registeredChunks(fixture.paper.worldId).isEmpty())
    }

    @Test
    fun `registered but unloaded chunk is rejected before PDC access can reload it`() {
        val fixture = Fixture()
        fixture.backend.registerChunk(fixture.paper.chunk)
        fixture.paper.worldChunkLoaded = false

        assertFailsWith<CompletionException> { fixture.backend.loadChunk(fixture.location).join() }
        assertFailsWith<CompletionException> {
            fixture.backend.saveChunk(ChunkCordSnapshot(fixture.location, emptyList())).join()
        }
        assertEquals(0, fixture.paper.pdcAccesses)
    }

    private class Fixture(scheduler: Scheduler = DirectScheduler) {
        val paper = PaperTestFixture(load = false)
        val location = WXZCord(paper.worldId, 0, 0)
        val key = NamespacedKey(paper.plugin, "chunk_data")
        val backend = PersistContainerChunkStorage(paper.plugin, scheduler)
        private val types = TypeManager()
        private val storage = ChunkBackendLifecycleCoordinator(backend, scheduler, ChunkCordSnapshotMapper(types), Executor(Runnable::run))
        val manager = SimpleComponentManager(storage, types)
        val module = CitadelModule(manager)
        val listener = PaperChunkListener(paper.plugin, manager, backend)

        init {
            module.registerDefinition(
                CitadelDefinition(PaperCitadelTypes.REINFORCED, emptyList()),
                DamageBreakPolicy,
                SimpleHealthBehavior(100.0)
            )
        }
    }
}
