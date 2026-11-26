package ru.fornax.fornaxcitadel.paper

import org.bukkit.NamespacedKey
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.addon.Addon
import ru.fornax.fornaxcitadel.addon.ComponentRegistration
import ru.fornax.fornaxcitadel.addon.CoreGameplay
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthDefinition
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.modules.health.MaturationDefinition
import ru.fornax.fornaxcitadel.modules.health.MaturingInitializer
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.JavaAddonPlugin.SHIELD_KEY
import ru.fornax.fornaxcitadel.paper.JavaAddonPlugin.Shield
import ru.fornax.fornaxcitadel.core.facade.HealthFacade
import ru.fornax.fornaxcitadel.core.facade.MaturationFacade
import ru.fornax.fornaxcitadel.paper.modules.chunks.PaperChunkListener
import ru.fornax.fornaxcitadel.paper.modules.chunks.PaperChunkService
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.health.PaperDamageValidator
import ru.fornax.fornaxcitadel.paper.storage.PersistContainerChunkStorage
import java.time.Duration
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
    fun `startup restores a saved component from an addon awaiting enable`() {
        val fixture = Fixture()
        fixture.storeShield(37.5)
        var shieldDeclarations = 0
        var emptyDeclarations = 0
        val shieldAddon = fixture.addon {
            shieldDeclarations++
            listOf(ComponentRegistration(SHIELD_KEY, Shield::class.java))
        }
        val emptyAddon = fixture.addon {
            emptyDeclarations++
            emptyList()
        }

        fixture.startWith(shieldAddon, fixture.paper.plugin, emptyAddon)

        val handle = assertNotNull(fixture.citadels.findExact(fixture.paper.position))
        assertEquals(37.5, handle.getComponent(Shield::class.java)?.health)
        assertEquals(1, shieldDeclarations)
        assertEquals(1, emptyDeclarations)
        fixture.closeStorage()
    }

    @Test
    fun `addon registers a working definition and health profile before chunk load`() {
        val fixture = Fixture()
        val type = Key("test", "early")
        val addon = object : Plugin by fixture.paper.plugin, Addon {
            override val key = Key("test", "addon")
            override fun registerDefinitions(definitions: CitadelDefinitionRegistry, health: HealthFacade, maturation: MaturationFacade) {
                assertFalse(fixture.manager.isChunkLoaded(fixture.paper.worldId, 0, 0))
                definitions.register(CitadelDefinition(type, listOf(HealthInitializer(health), MaturingInitializer())))
                health.registerDefinition(type, HealthDefinition(15.0))
                maturation.registerDefinition(type, MaturationDefinition(Duration.ZERO))
            }
        }

        fixture.startWith(addon)
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(PlaceAction(
            fixture.paper.context(fixture.paper.installer), fixture.paper.position, type)))
        val placed = assertNotNull(fixture.citadels.findExact(fixture.paper.position))
        assertEquals(15.0, fixture.coreGameplay.healthFacade.currentHealth(placed))
        assertEquals(Duration.ZERO, fixture.coreGameplay.maturationFacade.findDefinition(type)?.duration)
        assertTrue(fixture.coreGameplay.maturationFacade.isMature(placed))
        fixture.closeStorage()
    }

    @Test
    fun `saving a dirty chunk preserves the component payload of a missing addon`() {
        val fixture = Fixture()
        val shield = fixture.storeShield(12.5)

        fixture.startWith(fixture.paper.plugin)
        val handle = assertNotNull(fixture.citadels.findExact(fixture.paper.position))
        handle.putComponent(Health::class.java, Health(81.0))
        fixture.closeStorage()

        val snapshot = ChunkCordSnapshot.fromByteArray(fixture.paper.chunkData.values[fixture.key] as ByteArray)
        val components = snapshot.citadels.single().components.associateBy { it.key }
        assertContentEquals(shield.toByteArray(), components.getValue(SHIELD_KEY).payload)
        assertContentEquals(Health(81.0).toByteArray(), components.getValue(Health.KEY).payload)
    }

    @Test
    fun `two addons claiming the same key for different classes fail before reading chunks`() {
        val fixture = Fixture()
        val shieldAddon = fixture.addon { listOf(ComponentRegistration(SHIELD_KEY, Shield::class.java)) }
        val conflictingAddon = fixture.addon { listOf(ComponentRegistration(SHIELD_KEY, Health::class.java)) }

        val failure = assertFailsWith<IllegalArgumentException> { fixture.startWith(shieldAddon, conflictingAddon) }

        assertTrue(failure.message.orEmpty().contains(SHIELD_KEY.toString()))
        assertEquals(0, fixture.paper.pdcAccesses)
        assertFalse(fixture.manager.isChunkLoaded(fixture.paper.worldId, 0, 0))
    }

    @Test
    fun `world unload writes pending PDC and detaches all chunks before world disappearance`() {
        val fixture = Fixture()
        fixture.service.enable()
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(PlaceAction(
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
        fixture.service.enable()
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(PlaceAction(
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
        fixture.service.enable()

        dispatch(fixture.listener, WorldUnloadEvent(fixture.paper.world), fixture.paper.plugin)

        assertEquals(0, fixture.paper.worldSaveCount)
    }

    @Test
    fun `dirty unload synchronously writes PDC before unregister and reload restores type and damage`() {
        val fixture = Fixture()
        fixture.service.enable()
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(PlaceAction(
            fixture.paper.context(fixture.paper.installer), fixture.paper.position, PaperCitadelTypes.REINFORCED
        )))
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(DamageAction(
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
        fixture.service.enable()
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

        fixture.service.enable()
        fixture.listener.onLoad(ChunkLoadEvent(fixture.paper.chunk, false))

        assertFalse(fixture.manager.isChunkLoaded(fixture.paper.worldId, 0, 0))
        fixture.closeStorage()
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
        fixture.service.enable()
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(PlaceAction(
            fixture.paper.context(fixture.paper.installer), fixture.paper.position, PaperCitadelTypes.REINFORCED
        )))
        fixture.paper.enabled = false

        fixture.closeStorage()

        val snapshot = ChunkCordSnapshot.fromByteArray(fixture.paper.chunkData.values[fixture.key] as ByteArray)
        assertEquals(1, snapshot.citadels.size)
        assertFailsWith<CompletionException> { fixture.backend.loadChunk(fixture.location).join() }
    }

    @Test
    fun `lifecycle rejects incomplete save future instead of blocking the server thread`() {
        val pendingScheduler = object : Scheduler by DirectScheduler {
            override fun <T> runTask(location: WXZCord, task: Callable<T>) = CompletableFuture<T>()
        }
        val fixture = Fixture(pendingScheduler)
        fixture.service.enable()
        fixture.listener.onLoad(ChunkLoadEvent(fixture.paper.chunk, false))

        assertFailsWith<IllegalStateException> { fixture.service.saveAll() }
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
        fixture.service.enable()
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(PlaceAction(
            fixture.paper.context(fixture.paper.installer), fixture.paper.position, PaperCitadelTypes.REINFORCED
        )))
        fixture.service.saveAll()
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
        val manager = ComponentManager(storage, types)
        val actions = ActionManager()
        val citadels = CitadelFacade(manager, ExactTestCitadelResolver)
        val coreGameplay = CoreGameplay(citadels, ActionFacade(actions))
        val service = PaperChunkService(paper.plugin, manager, backend, 20L)
        val listener = PaperChunkListener(service)

        fun storeShield(health: Double): Shield {
            val savedTypes = TypeManager()
            val type = savedTypes.registerType(SHIELD_KEY, Shield::class.java)
            val chunk = ChunkCord(location)
            val entity = chunk.putCitadel(paper.position.x, paper.position.y, paper.position.z)
            val shield = Shield().also { it.health = health }
            chunk.putComponent(entity, type, shield)
            paper.chunkData.values[key] = ChunkCordSnapshotMapper(savedTypes).snapshot(chunk).toByteArray()
            return shield
        }

        fun addon(declarations: () -> List<ComponentRegistration>): Plugin =
            object : Plugin by paper.plugin, Addon {
                override fun isEnabled() = false
                override val key: Key
                    get() = Key.key("test:test")

                override fun defaultComponents() = declarations()
                override fun onEnable(): Unit = error("Only Paper may enable the plugin")
                override fun onDisable(): Unit = error("Only Paper may disable the plugin")
            }

        fun startWith(vararg plugins: Plugin) {
            service.enableWithAddons(plugins, citadels, coreGameplay)
        }

        fun closeStorage() {
            service.disable()
            manager.closeAndSaveAll().join()
            backend.flushPendingWorldSaves()
            backend.clearRegisteredChunks()
        }

        init {
            coreGameplay.health.registerDefinition(PaperCitadelTypes.REINFORCED, HealthDefinition(100.0))
            coreGameplay.registerDefinition(
                CitadelDefinition(PaperCitadelTypes.REINFORCED, listOf(HealthInitializer(coreGameplay.healthFacade)))
            )
            actions.registerValidator(
                PaperDamageValidator(citadels, coreGameplay.health, true))
        }
    }
}
