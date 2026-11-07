package ru.fornax.fornaxcitadel.paper

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationPublisher
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationSubscription
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.health.DamagePolicy
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.paper.modules.chunks.PaperChunkService
import ru.fornax.fornaxcitadel.paper.modules.observation.Full3DObservation
import ru.fornax.fornaxcitadel.paper.modules.observation.Full3DObservationSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.scheduler.PaperScheduler
import ru.fornax.fornaxcitadel.paper.storage.PersistContainerChunkStorage
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PaperFull3DObservationTest {
    @Test
    fun `initial snapshot covers seven by seven chunks and seven vertical sections in this world`() {
        Fixture().use { fixture ->
            val inside = setOf(
                fixture.place(-48, 16, -48),
                fixture.place(63, 127, 63),
                fixture.place(0, 64, 0)
            )
            fixture.place(-49, 64, 0)
            fixture.place(64, 64, 0)
            fixture.place(0, 15, 0)
            fixture.place(0, 128, 0)
            fixture.place(0, 64, -49)
            fixture.place(0, 64, 64)
            fixture.place(0, 64, 0, UUID(8, 9))

            val publications = fixture.observe()

            assertEquals(listOf(inside), publications)
            assertEquals((-3..3).flatMap { x -> (-3..3).map { z -> x to z } }.toSet(),
                fixture.queries.map { it.x to it.z }.toSet())
            assertTrue(fixture.queries.all { it.world == fixture.paper.worldId })
            assertEquals(0, fixture.paper.blockLookups)
        }
    }

    @Test
    fun `negative block coordinates use floor based sections on every axis`() {
        Fixture(Full3DObservationSettings(0, 0)).use { fixture ->
            fixture.paper.installer.location = Location(fixture.paper.world, -0.1, -0.1, -0.1)
            val inside = setOf(fixture.place(-16, -16, -16), fixture.place(-1, -1, -1))
            fixture.place(0, -1, -1)
            fixture.place(-1, 0, -1)
            fixture.place(-1, -1, 0)
            fixture.place(-17, -1, -1)
            fixture.place(-1, -17, -1)

            assertEquals(listOf(inside), fixture.observe())
            assertEquals(setOf(-1 to -1), fixture.queries.map { it.x to it.z }.toSet())
        }
    }

    @Test
    fun `movement within a section and rotation do not rescan or publish`() {
        Fixture(Full3DObservationSettings(0, 0)).use { fixture ->
            val first = fixture.place(0, 64, 0)
            val second = fixture.place(16, 64, 0)
            val publications = fixture.observe()
            val initialQueries = fixture.queries.size

            fixture.move(Location(fixture.paper.world, 15.9, 79.9, 15.9, 90f, 30f))
            fixture.paper.runScheduledTasks()

            assertEquals(initialQueries, fixture.queries.size)
            assertEquals(listOf(setOf(first)), publications)

            fixture.move(Location(fixture.paper.world, 16.0, 79.9, 15.9))
            fixture.paper.runScheduledTasks()

            assertEquals(listOf(setOf(first), setOf(second)), publications)
        }
    }

    @Test
    fun `movement reads next tick location and coalesces teleport and move notifications`() {
        Fixture(Full3DObservationSettings(0, 0)).use { fixture ->
            val first = fixture.place(0, 64, 0)
            val last = fixture.place(32, 64, 0)
            fixture.place(96, 64, 0)
            val publications = fixture.observe()
            val initialQueries = fixture.queries.size
            val viewer = fixture.paper.installer
            val cancelled = PlayerMoveEvent(viewer.player, viewer.location.clone(),
                Location(fixture.paper.world, 96.0, 64.0, 0.0))
            fixture.dispatch(cancelled)
            cancelled.isCancelled = true // A later listener cancels before the player's position changes.
            fixture.paper.runScheduledTasks()
            assertEquals(initialQueries, fixture.queries.size)
            assertEquals(listOf(setOf(first)), publications)

            val cancelledTeleport = PlayerTeleportEvent(viewer.player, viewer.location.clone(),
                Location(fixture.paper.world, 96.0, 64.0, 0.0), PlayerTeleportEvent.TeleportCause.PLUGIN)
            fixture.dispatch(cancelledTeleport)
            cancelledTeleport.isCancelled = true
            fixture.paper.runScheduledTasks()
            assertEquals(initialQueries, fixture.queries.size)
            assertEquals(listOf(setOf(first)), publications)

            fixture.move(Location(fixture.paper.world, 16.0, 64.0, 0.0))
            fixture.dispatch(PlayerTeleportEvent(viewer.player, viewer.location.clone(),
                Location(fixture.paper.world, 96.0, 64.0, 0.0), PlayerTeleportEvent.TeleportCause.PLUGIN))
            viewer.location = Location(fixture.paper.world, 32.0, 64.0, 0.0)
            fixture.paper.runScheduledTasks()

            assertEquals(listOf(setOf(first), setOf(last)), publications)

            val respawnLocation = Location(fixture.paper.world, 0.0, 64.0, 0.0)
            fixture.dispatch(PlayerRespawnEvent(viewer.player, respawnLocation, false, false, false,
                PlayerRespawnEvent.RespawnReason.DEATH))
            assertEquals(listOf(setOf(first), setOf(last)), publications)
            viewer.location = respawnLocation
            fixture.paper.runScheduledTasks()
            assertEquals(listOf(setOf(first), setOf(last), setOf(first)), publications)
        }
    }

    @Test
    fun `world change uses new world identity and unsupported coordinates publish an empty set`() {
        Fixture(Full3DObservationSettings(0, 0)).use { fixture ->
            fixture.place(0, 64, 0)
            val otherId = UUID(11, 12)
            val otherWorld = paperProxy<World>("other world") { method, _ ->
                when (method.name) {
                    "getUID" -> otherId
                    "getMinHeight" -> -64
                    "getMaxHeight" -> 320
                    else -> unsupported(method)
                }
            }
            val other = fixture.place(0, 64, 0, otherId)
            val publications = fixture.observe()
            fixture.paper.installer.location = Location(otherWorld, 0.0, 64.0, 0.0)
            fixture.dispatch(PlayerChangedWorldEvent(fixture.paper.installer.player, fixture.paper.world))
            fixture.paper.runScheduledTasks()
            assertEquals(setOf(other), publications.last())

            fixture.move(Location(otherWorld, Int.MAX_VALUE.toDouble(), 64.0, 0.0))
            fixture.paper.runScheduledTasks()
            assertTrue(publications.last().isEmpty())
        }
    }

    @Test
    fun `vertical search clips to storage bounds`() {
        Fixture().use { fixture ->
            val bottom = fixture.place(0, -64, 0)
            val top = fixture.place(0, 319, 0)
            fixture.paper.installer.location = Location(fixture.paper.world, 0.0, -64.0, 0.0)
            val publications = fixture.observe()
            assertEquals(setOf(bottom), publications.last())
            fixture.move(Location(fixture.paper.world, 0.0, 319.0, 0.0))
            fixture.paper.runScheduledTasks()
            assertEquals(setOf(top), publications.last())
        }
    }

    @Test
    fun `stationary viewers refresh for nearby placement removal and replacement only`() {
        Fixture(Full3DObservationSettings(0, 0)).use { fixture ->
            val publications = fixture.observe()
            val initialQueries = fixture.queries.size
            fixture.place(64, 64, 0)
            fixture.paper.runScheduledTasks()
            assertEquals(initialQueries, fixture.queries.size)
            assertEquals(listOf(emptySet()), publications)

            val position = fixture.place(1, 64, 1)
            fixture.paper.runScheduledTasks()
            assertEquals(setOf(position), publications.last())

            fixture.paper.installer.permissions.add("fornaxcitadel.admin")
            assertEquals(ActionResult.SUCCESS, fixture.paper.module.actions.execute(RemoveAction(
                fixture.paper.context(fixture.paper.installer), position)))
            fixture.paper.runScheduledTasks()
            assertTrue(publications.last().isEmpty())

            fixture.place(1, 64, 1)
            fixture.paper.runScheduledTasks()
            assertEquals(setOf(position), publications.last())
        }
    }

    @Test
    fun `placement from publication callback in the newly entered cell triggers another refresh`() {
        Fixture(Full3DObservationSettings(0, 0)).use { fixture ->
            val existing = fixture.place(16, 64, 0)
            val added = CitadelPosition(fixture.paper.worldId, 17, 64, 0)
            val publications = mutableListOf<Set<CitadelPosition>>()
            var placed = false
            fixture.provider.observe(fixture.paper.installer.player, ObservationPublisher { handles ->
                val positions = handles.map { it.position }.toSet()
                publications.add(positions)
                if (existing in positions && !placed) {
                    placed = true
                    fixture.place(added.x, added.y, added.z)
                }
            }).use {
                fixture.move(Location(fixture.paper.world, 16.0, 64.0, 0.0))
                fixture.paper.runScheduledTasks()
                assertEquals(setOf(existing), publications.last())

                fixture.paper.runScheduledTasks()
                assertEquals(setOf(existing, added), publications.last())
            }
        }
    }

    @Test
    fun `loaded chunk notifications refresh stationary viewers after unload and reload`() {
        Fixture(Full3DObservationSettings(0, 0)).use { fixture ->
            fixture.chunks.enable()
            val position = fixture.place(1, 64, 1)
            val publications = fixture.observe()
            assertEquals(setOf(position), publications.last())

            fixture.chunks.onUnload(ChunkUnloadEvent(fixture.paper.chunk, true))
            fixture.paper.runScheduledTasks()
            assertTrue(publications.last().isEmpty())

            fixture.chunks.onLoad(ChunkLoadEvent(fixture.paper.chunk, false))
            fixture.paper.runScheduledTasks()
            assertEquals(setOf(position), publications.last())
        }
    }

    @Test
    fun `late cancelled world unload replaces stale observations with restored entities`() {
        PersistenceFixture().use { fixture ->
            val publications = fixture.observe()
            val original = publications.single().single()
            val event = WorldUnloadEvent(fixture.paper.world)

            fixture.chunks.onWorldUnload(event)
            assertFalse(original.isCurrent)
            event.isCancelled = true
            fixture.paper.freshChunkWrappers = true
            fixture.paper.runScheduledTasks()

            val restored = publications.last().single()
            assertEquals(original.position, restored.position)
            assertNotEquals(original.runtimeId, restored.runtimeId)
            assertTrue(restored.isCurrent)
            assertEquals(1, fixture.paper.worldSaveCount)
        }
    }

    @Test
    fun `failed unload removes stale observations and retry publishes recovered entity`() {
        PersistenceFixture().use { fixture ->
            val publications = fixture.observe()
            val original = publications.single().single()
            fixture.paper.chunkData.writeFailure = IllegalStateException("PDC write failed")

            fixture.chunks.onUnload(ChunkUnloadEvent(fixture.paper.chunk, true))
            fixture.paper.runScheduledTasks()
            assertFalse(original.isCurrent)
            assertTrue(publications.last().isEmpty())

            fixture.paper.chunkData.writeFailure = null
            fixture.chunks.onLoad(ChunkLoadEvent(fixture.paper.chunk, false))
            fixture.paper.runScheduledTasks()

            val restored = publications.last().single()
            assertEquals(original.position, restored.position)
            assertNotEquals(original.runtimeId, restored.runtimeId)
            assertTrue(restored.isCurrent)
        }
    }

    @Test
    fun `subscribers share listeners and last close cancels work across reuse`() {
        Fixture(Full3DObservationSettings(0, 0)).use { fixture ->
            val first = mutableListOf<Set<CitadelPosition>>()
            val second = mutableListOf<Set<CitadelPosition>>()
            val a = fixture.provider.observe(fixture.paper.installer.player, fixture.capture(first))
            val b = fixture.provider.observe(fixture.paper.stranger.player, fixture.capture(second))
            assertEquals(1, fixture.paper.listenerRegistrations.size)
            a.close()
            a.close()
            assertEquals(1, fixture.paper.listenerRegistrations.size)

            fixture.place(1, 64, 1)
            val oldTask = fixture.paper.scheduledTasks.single { !it.cancelled && !it.repeating }
            b.close()
            assertTrue(fixture.paper.listenerRegistrations.isEmpty())
            assertTrue(oldTask.cancelled)

            val current = fixture.observe()
            val publicationsBeforeStaleCallback = current.size
            oldTask.action.run()
            assertEquals(publicationsBeforeStaleCallback, current.size)
            fixture.provider.close()
            fixture.provider.close()
            assertTrue(current.last().isEmpty())
            assertTrue(fixture.paper.listenerRegistrations.isEmpty())
        }
    }

    @Test
    fun `failed initial publication rolls back listener registration`() {
        Fixture().use { fixture ->
            assertFailsWith<IllegalStateException> {
                fixture.provider.observe(fixture.paper.installer.player, ObservationPublisher {
                    error("publication failed")
                })
            }
            assertTrue(fixture.paper.listenerRegistrations.isEmpty())
            assertEquals(listOf(emptySet()), fixture.observe())
            assertEquals(1, fixture.paper.listenerRegistrations.size)
        }
    }

    @Test
    fun `partial listener registration failure detaches domain hooks and permits another observation`() {
        Fixture().use { fixture ->
            fixture.paper.listenerRegistrationFailure = IllegalStateException("registration failed")
            assertFailsWith<IllegalStateException> { fixture.observe() }
            assertTrue(fixture.paper.listenerRegistrations.isEmpty())

            val position = fixture.place(1, 64, 1)
            assertTrue(fixture.paper.scheduledTasks.none { !it.cancelled })
            fixture.paper.listenerRegistrationFailure = null
            assertEquals(listOf(setOf(position)), fixture.observe())
            assertEquals(1, fixture.paper.listenerRegistrations.size)
        }
    }

    @Test
    fun `negative observation radii are rejected`() {
        assertFailsWith<IllegalArgumentException> { Full3DObservationSettings(-1, 0) }
        assertFailsWith<IllegalArgumentException> { Full3DObservationSettings(0, -1) }
    }

    private class PersistenceFixture : AutoCloseable {
        val paper = PaperTestFixture(load = false, registerTypes = false, initializeSystems = false,
            initializeModule = false, trackListenerLifecycle = true)
        private val scheduler = PaperScheduler(paper.plugin)
        private val types = TypeManager()
        private val backend = PersistContainerChunkStorage(paper.plugin, scheduler)
        private val storage = ChunkBackendLifecycleCoordinator(backend, scheduler,
            ChunkCordSnapshotMapper(types), Executor(Runnable::run))
        private val manager = ComponentManager(storage, types)
        private val module = CitadelModule(manager, ManualScheduler())
        val chunks = PaperChunkService(paper.plugin, manager, backend, 100)
        private val provider = Full3DObservation(paper.plugin, manager, module.lifecycle, chunks,
            Full3DObservationSettings(0, 0))

        init {
            val type = Key("test", "observed")
            module.registerDefinition(CitadelDefinition(type, emptyList()), DamagePolicy.ALLOW)
            module.enable()
            chunks.enable()
            assertEquals(ActionResult.SUCCESS, module.actions.execute(PlaceAction(
                ActionContext.SYSTEM, paper.position, type)))
        }

        fun observe() = mutableListOf<List<CitadelHandle>>().also { publications ->
            provider.observe(paper.installer.player, ObservationPublisher { publications.add(it.toList()) })
        }

        override fun close() {
            provider.close()
            paper.chunkData.writeFailure = null
            chunks.disable()
            module.disable()
            manager.closeAndSaveAll().join()
            scheduler.close()
            paper.close()
        }
    }

    private class Fixture(settings: Full3DObservationSettings = Full3DObservationSettings()) : AutoCloseable {
        val paper = PaperTestFixture(initializeSystems = false, trackListenerLifecycle = true, resolveMaterials = false)
        val queries = mutableListOf<WXZCord>()
        private val observedStorage = object : CitadelStorage by paper.storage {
            override fun getIfChunkLoaded(location: WXZCord): ChunkCord? {
                queries.add(location)
                return paper.storage.getIfChunkLoaded(location)
            }
        }
        private val manager = ComponentManager(observedStorage, paper.types)
        private val scheduler = PaperScheduler(paper.plugin)
        private val backend = PersistContainerChunkStorage(paper.plugin, scheduler)
        val chunks = PaperChunkService(paper.plugin, paper.manager, backend, 100)
        val provider = Full3DObservation(paper.plugin, manager, paper.module.lifecycle, chunks, settings)
        private val subscriptions = mutableListOf<ObservationSubscription>()

        fun place(x: Int, y: Int, z: Int, world: UUID = paper.worldId): CitadelPosition {
            paper.manager.loadChunk(world, x shr 4, z shr 4).join()
            val position = CitadelPosition(world, x, y, z)
            assertEquals(ActionResult.SUCCESS, paper.module.actions.execute(PlaceAction(
                paper.context(paper.installer), position, PaperCitadelTypes.REINFORCED)))
            return position
        }

        fun capture(target: MutableList<Set<CitadelPosition>>) = ObservationPublisher { citadels ->
            target.add(citadels.map { it.position }.toSet())
        }

        fun observe(): MutableList<Set<CitadelPosition>> = mutableListOf<Set<CitadelPosition>>().also {
            subscriptions.add(provider.observe(paper.installer.player, capture(it)))
        }

        fun move(location: Location) {
            val viewer = paper.installer
            val event = PlayerMoveEvent(viewer.player, viewer.location.clone(), location)
            dispatch(event)
            viewer.location = location
        }

        fun dispatch(event: Event) {
            val listener = paper.listenerRegistrations.map { it.first }.single { listener ->
                listener.javaClass.methods.any {
                    it.isAnnotationPresent(EventHandler::class.java) &&
                        it.parameterTypes.contentEquals(arrayOf(event.javaClass))
                }
            }
            dispatch(listener, event, paper.plugin)
        }

        override fun close() {
            subscriptions.forEach(ObservationSubscription::close)
            provider.close()
            chunks.disable()
            scheduler.close()
            paper.close()
        }
    }
}
