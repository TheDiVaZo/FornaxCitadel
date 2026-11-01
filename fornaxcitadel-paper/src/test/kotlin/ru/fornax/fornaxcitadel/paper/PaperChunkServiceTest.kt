package ru.fornax.fornaxcitadel.paper

import org.bukkit.event.Listener
import org.bukkit.event.world.WorldUnloadEvent
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.breaking.DamageBreakPolicy
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.SimpleHealthBehavior
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.modules.chunks.PaperChunkListener
import ru.fornax.fornaxcitadel.paper.modules.chunks.PaperChunkService
import ru.fornax.fornaxcitadel.paper.storage.PersistContainerChunkStorage
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperChunkServiceTest {
    @Test
    fun `suspension preserves persisted data and types and reloads only current live chunks`() {
        Fixture().use { fixture ->
            fixture.service.enable()
            fixture.place()
            fixture.damage(25.0)
            val original = assertNotNull(fixture.find())
            val kindType = fixture.types.getType(CitadelKind::class.java)
            val healthType = fixture.types.getType(Health::class.java)

            fixture.service.disable()
            fixture.service.disable()

            assertFalse(original.isCurrent)
            assertTrue(fixture.backend.registeredChunks().isEmpty())
            assertEquals(1, fixture.paper.worldSaveCount)
            assertSame(kindType, fixture.types.getType(CitadelKind::class.java))
            assertSame(healthType, fixture.types.getType(Health::class.java))
            fixture.paper.worldChunkLoaded = false
            fixture.service.enable()
            assertNull(fixture.find())
            assertTrue(fixture.backend.registeredChunks().isEmpty())

            fixture.service.disable()
            fixture.paper.worldChunkLoaded = true
            fixture.paper.freshChunkWrappers = true
            fixture.service.enable()
            assertEquals(75.0, fixture.health())
            assertEquals(1, fixture.backend.registeredChunks().size)
            fixture.damage(5.0)
            assertEquals(70.0, fixture.health())
        }
    }

    @Test
    fun `subscriptions and autosave are idempotent and do not cancel unrelated work`() {
        Fixture().use { fixture ->
            val unrelated = object : Listener {}
            fixture.paper.server.pluginManager.registerEvents(unrelated, fixture.paper.plugin)
            val unrelatedTask = fixture.paper.server.scheduler.runTask(fixture.paper.plugin, Runnable {})
            fixture.service.disable()
            fixture.service.enable()
            fixture.service.enable()
            val listener = fixture.paper.listenerRegistrations.single { it.first !== unrelated }.first
            assertTrue(listener is PaperChunkListener)
            val autosave = fixture.paper.scheduledTasks.single { it.repeating }
            assertEquals(20L, autosave.delayTicks)
            assertEquals(20L, autosave.periodTicks)
            fixture.place()
            fixture.paper.advanceTicks(19)
            assertTrue(fixture.paper.chunkData.values.isEmpty())
            fixture.paper.advanceTicks(1)
            assertTrue(fixture.paper.chunkData.values.isNotEmpty())

            fixture.service.disable()
            assertTrue(autosave.cancelled)
            assertFalse(unrelatedTask.isCancelled)
            assertEquals(listOf(unrelated), fixture.paper.listenerRegistrations.map { it.first })
            fixture.service.enable()
            assertSame(listener, fixture.paper.listenerRegistrations.single { it.first !== unrelated }.first)
            assertEquals(1, fixture.paper.scheduledTasks.count { it.repeating && !it.cancelled })
        }
    }

    @Test
    fun `failed PDC save preserves the unload snapshot and binding until a successful retry`() {
        Fixture().use { fixture ->
            fixture.service.enable()
            fixture.place()
            fixture.damage(30.0)
            val failure = IllegalStateException("PDC write failed")
            fixture.paper.chunkData.writeFailure = failure

            assertSame(failure, assertFailsWith<IllegalStateException> { fixture.service.disable() })
            assertFalse(fixture.manager.isChunkLoaded(fixture.paper.worldId, 0, 0))
            assertEquals(1, fixture.backend.registeredChunks().size)
            assertTrue(fixture.paper.chunkData.values.isEmpty())
            assertTrue(fixture.paper.listenerRegistrations.isEmpty())
            assertTrue(fixture.paper.scheduledTasks.all { it.cancelled })
            assertSame(failure, assertFailsWith<IllegalStateException> { fixture.service.enable() })
            assertTrue(fixture.paper.listenerRegistrations.isEmpty())

            fixture.paper.chunkData.writeFailure = null
            fixture.service.enable()
            assertEquals(70.0, fixture.health())
            assertEquals(1, fixture.paper.worldSaveCount)
            assertEquals(1, fixture.paper.listenerRegistrations.size)
        }
    }

    @Test
    fun `world flush failures retain PDC bindings and can be retried while disabled`() {
        Fixture().use { fixture ->
            fixture.service.enable()
            fixture.place()
            val failure = IllegalStateException("World save failed")
            fixture.paper.worldSaveFailure = failure

            assertSame(failure, assertFailsWith<IllegalStateException> { fixture.service.disable() })
            assertTrue(fixture.paper.chunkData.values.isNotEmpty())
            assertEquals(1, fixture.backend.registeredChunks().size)
            assertTrue(fixture.backend.hasPendingChunkSave(fixture.paper.chunk))
            assertSame(failure, assertFailsWith<IllegalStateException> { fixture.service.enable() })
            assertTrue(fixture.paper.listenerRegistrations.isEmpty())

            fixture.paper.worldSaveFailure = null
            fixture.service.disable()
            assertTrue(fixture.backend.registeredChunks().isEmpty())
            assertEquals(1, fixture.paper.worldSaveCount)
            fixture.service.enable()
            assertEquals(100.0, fixture.health())
        }
    }

    @Test
    fun `retained snapshot restores data after failed world flush and an unsaved Bukkit reload`() {
        Fixture().use { fixture ->
            fixture.service.enable()
            fixture.place()
            fixture.damage(35.0)
            fixture.paper.worldSaveFailure = IllegalStateException("World save failed")
            assertFailsWith<IllegalStateException> { fixture.service.disable() }
            val original = (fixture.paper.chunkData.values.values.single() as ByteArray).copyOf()

            fixture.paper.worldSaveFailure = null
            fixture.paper.worldChunkLoaded = false
            fixture.paper.chunkData.values.clear()
            assertFailsWith<IllegalStateException> { fixture.service.disable() }
            assertTrue(fixture.backend.hasPendingChunkSave(fixture.paper.chunk))
            assertEquals(1, fixture.backend.registeredChunks().size)

            fixture.paper.worldChunkLoaded = true
            fixture.paper.freshChunkWrappers = true
            fixture.backend.registerChunk(fixture.paper.chunkWrapper())
            fixture.backend.loadChunk(WXZCord(fixture.paper.worldId, 0, 0)).join()
            assertContentEquals(original, fixture.paper.chunkData.values.values.single() as ByteArray)

            // Suspension must also restore the retained bytes without an intervening load.
            fixture.paper.chunkData.values.clear()
            fixture.service.enable()
            assertContentEquals(original, fixture.paper.chunkData.values.values.single() as ByteArray)
            assertEquals(65.0, fixture.health())
            assertEquals(PaperCitadelTypes.REINFORCED,
                fixture.find()?.getComponent(CitadelKind::class.java)?.definitionKey)
        }
    }

    @Test
    fun `old world reconciliation cannot detach bindings from a new enable cycle`() {
        Fixture().use { fixture ->
            fixture.service.enable()
            fixture.place()
            val listener = fixture.paper.listenerRegistrations.single().first
            dispatch(listener, WorldUnloadEvent(fixture.paper.world), fixture.paper.plugin)
            val reconciliation = fixture.paper.scheduledTasks.single { !it.repeating }
            fixture.service.disable()
            assertTrue(reconciliation.cancelled)
            fixture.service.enable()
            val restored = assertNotNull(fixture.find())
            fixture.paper.worldAvailable = false

            reconciliation.action.run()

            assertTrue(restored.isCurrent)
            assertEquals(1, fixture.backend.registeredChunks().size)
            fixture.paper.worldAvailable = true
        }
    }

    @Test
    fun `failed autosave scheduling rolls back loaded data and subscriptions and allows retry`() {
        Fixture().use { fixture ->
            val failure = IllegalStateException("Scheduler rejected the task")
            fixture.paper.schedulerFailure = failure

            assertSame(failure, assertFailsWith<IllegalStateException> { fixture.service.enable() })
            assertTrue(fixture.paper.listenerRegistrations.isEmpty())
            assertTrue(fixture.backend.registeredChunks().isEmpty())
            assertFalse(fixture.manager.isChunkLoaded(fixture.paper.worldId, 0, 0))

            fixture.paper.schedulerFailure = null
            fixture.service.enable()
            fixture.place()
            assertEquals(100.0, fixture.health())
        }
    }

    private class Fixture : AutoCloseable {
        val paper = PaperTestFixture(load = false, initializeSystems = false, trackListenerLifecycle = true)
        val types = TypeManager()
        val backend = PersistContainerChunkStorage(paper.plugin, DirectScheduler)
        private val storage = ChunkBackendLifecycleCoordinator(backend, DirectScheduler,
            ChunkCordSnapshotMapper(types), Executor(Runnable::run))
        val manager = SimpleComponentManager(storage, types)
        private val module = CitadelModule(manager)
        val service = PaperChunkService(paper.plugin, manager, backend, 20L)

        init {
            module.registerDefinition(CitadelDefinition(PaperCitadelTypes.REINFORCED, emptyList()),
                DamageBreakPolicy, SimpleHealthBehavior(100.0))
            module.enable()
        }

        fun place() {
            assertEquals(ActionResult.SUCCESS, module.actions.execute(PlaceAction(
                paper.context(paper.installer), paper.position, PaperCitadelTypes.REINFORCED)))
        }

        fun damage(amount: Double) {
            assertEquals(ActionResult.SUCCESS, module.actions.execute(DamageAction(
                paper.context(paper.stranger), paper.position, amount)))
        }

        fun find() = manager.findCitadel(paper.worldId, 1, 64, 2)
        fun health() = assertNotNull(find()).getComponent(Health::class.java)?.health

        override fun close() {
            paper.mainThread = true
            paper.worldAvailable = true
            paper.worldChunkLoaded = true
            paper.worldSaveFailure = null
            paper.chunkData.writeFailure = null
            paper.schedulerFailure = null
            service.disable()
            manager.closeAndSaveAll().join()
            module.disable()
            paper.close()
        }
    }
}
