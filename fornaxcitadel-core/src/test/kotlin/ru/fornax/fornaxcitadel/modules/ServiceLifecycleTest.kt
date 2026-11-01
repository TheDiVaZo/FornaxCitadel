package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleNotifications
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.scheduler.ManualScheduler
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.DamagePolicy
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.health.MaturingHealthBehavior
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.place.PlaceService
import ru.fornax.fornaxcitadel.modules.remove.RemovalPolicy
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServiceLifecycleTest {
    @Test
    fun `constructors register component types before enabling any handlers`() {
        val fixture = Fixture()
        fixture.prepare()

        assertNotNull(fixture.types.getType(CitadelKind::class.java))
        assertNotNull(fixture.types.getType(Health::class.java))
        assertNotNull(fixture.types.getType(Maturing::class.java))
        assertEquals(ActionResult.DENIED, fixture.place())
        ACTION_KEYS.forEach { assertNull(fixture.actions.processorCoordinator.getProcessor(it)) }

        fixture.enable()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        val citadel = assertNotNull(fixture.find())
        assertEquals(TYPE, citadel.getComponent(CitadelKind::class.java)?.definitionKey)
        assertEquals(1_000L, citadel.getComponent(Maturing::class.java)?.startedAtMillis)
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(DamageAction(ActionContext.SYSTEM, POSITION, 2.0)))
        assertEquals(8.0, fixture.currentHealth())
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(DamageAction(ActionContext.SYSTEM, POSITION, 3.0)))
        assertEquals(5.0, fixture.currentHealth())
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(RemoveAction(ActionContext.SYSTEM, POSITION)))
        assertNull(fixture.find())
    }

    @Test
    fun `repeated lifecycle calls preserve component types live data and rules`() {
        val fixture = Fixture()
        fixture.prepare()
        val componentTypes = listOf(
            fixture.types.getType(CitadelKind::class.java),
            fixture.types.getType(Health::class.java),
            fixture.types.getType(Maturing::class.java)
        )
        fixture.disable()
        fixture.disable()
        fixture.enable()
        fixture.enable()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        val original = assertNotNull(fixture.find())
        val health = original.getComponent(Health::class.java)
        val maturation = original.getComponent(Maturing::class.java)
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(DamageAction(ActionContext.SYSTEM, POSITION, 2.0)))
        fixture.storage.chunk!!.resetDirty()

        repeat(2) {
            fixture.disable()
            fixture.disable()
            ACTION_KEYS.forEach { assertNull(fixture.actions.processorCoordinator.getProcessor(it)) }
            assertEquals(ActionResult.DENIED, fixture.actions.execute(DamageAction(ActionContext.SYSTEM, POSITION, 50.0)))
            assertSame(health, fixture.find()?.getComponent(Health::class.java))
            assertSame(maturation, fixture.find()?.getComponent(Maturing::class.java))
            assertEquals(8.0, fixture.currentHealth())
            assertTrue(original.isCurrent)
            assertFalse(fixture.storage.chunk!!.isDirty)
            fixture.enable()
            fixture.enable()
        }

        assertEquals(componentTypes, listOf(
            fixture.types.getType(CitadelKind::class.java),
            fixture.types.getType(Health::class.java),
            fixture.types.getType(Maturing::class.java)
        ))
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(DamageAction(ActionContext.SYSTEM, POSITION, 8.0)))
        assertNull(fixture.find())
    }

    @Test
    fun `health toggles only its own damage validator and restores type protection`() {
        val fixture = Fixture()
        fixture.prepare(DamagePolicy.DENY)
        fixture.enable()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        val damage = DamageAction(ActionContext.SYSTEM, POSITION, 20.0)
        val external = object : ActionValidator<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun validate(action: DamageAction) = ActionValidator.Result.Success
        }
        fixture.actions.validatorCoordinator.registerValidator(external)
        assertEquals(ActionResult.DENIED, fixture.actions.execute(damage))

        fixture.health.disable()
        fixture.health.disable()
        assertSame(ActionValidator.Result.Success, fixture.actions.validatorCoordinator.validate(damage))
        fixture.health.enable()
        fixture.health.enable()
        assertEquals(ActionResult.DENIED, fixture.actions.execute(damage))
        assertEquals(10.0, fixture.currentHealth())
        fixture.disable()
        assertSame(ActionValidator.Result.Success, fixture.actions.validatorCoordinator.validate(damage))
        assertTrue(fixture.actions.validatorCoordinator.unregisterValidator(external))
        assertSame(ActionValidator.Result.Neutral, fixture.actions.validatorCoordinator.validate(damage))
    }

    @Test
    fun `disable never removes a replacement processor for the same action`() {
        val fixture = Fixture()
        fixture.enable()
        val coordinator = fixture.actions.processorCoordinator
        val own = assertNotNull(coordinator.getProcessor(DamageAction.KEY))
        assertTrue(coordinator.unregisterProcessor(own))
        val replacement = damageProcessor()
        coordinator.registerProcessor(replacement)

        fixture.disable()
        assertSame(replacement, coordinator.getProcessor(DamageAction.KEY))
        assertFalse(coordinator.unregisterProcessor(own))
        assertSame(replacement, coordinator.getProcessor(DamageAction.KEY))
    }

    @Test
    fun `root lifecycle preserves definitions and data but discards pending actions`() {
        val fixture = RootFixture()
        val module = fixture.module
        val definition = CitadelDefinition(TYPE, emptyList())
        module.registerDefinition(definition, DamagePolicy.ALLOW, behavior())
        fixture.load()
        assertEquals(ActionResult.DENIED, module.actions.execute(placeAction()))
        assertTrue(fixture.scheduler.tasks.isEmpty())
        module.enable()
        module.enable()
        val firstTimer = fixture.scheduler.tasks.single()
        assertEquals(ActionResult.SUCCESS, module.actions.execute(placeAction()))
        val original = assertNotNull(fixture.manager.findCitadel(POSITION.world, POSITION.x, POSITION.y, POSITION.z))
        val health = original.getComponent(Health::class.java)
        module.actions.post(DamageAction(ActionContext.SYSTEM, POSITION, 100.0))

        module.disable()
        module.disable()
        assertTrue(firstTimer.isCancelled)
        ACTION_KEYS.forEach { assertNull(module.actions.processorCoordinator.getProcessor(it)) }
        assertTrue(original.isCurrent)
        assertSame(health, original.getComponent(Health::class.java))
        assertNotNull(module.definitions.find(TYPE))
        module.enable()
        assertEquals(2, fixture.scheduler.tasks.size)
        fixture.scheduler.tick()
        assertEquals(0, module.actions.drainPosted())
        assertEquals(ActionResult.SUCCESS, module.actions.execute(DamageAction(ActionContext.SYSTEM, POSITION, 2.0)))
        assertEquals(8.0, original.getComponent(Health::class.java)?.health)
    }

    @Test
    fun `failed root enable rolls back owned registrations and can be retried`() {
        val fixture = RootFixture()
        val module = fixture.module
        val external = damageProcessor()
        module.actions.processorCoordinator.registerProcessor(external)

        assertFailsWith<IllegalArgumentException> { module.enable() }
        assertTrue(fixture.scheduler.tasks.isEmpty())
        assertSame(external, module.actions.processorCoordinator.getProcessor(DamageAction.KEY))
        for (key in ACTION_KEYS - DamageAction.KEY) assertNull(module.actions.processorCoordinator.getProcessor(key))
        assertNotNull(fixture.types.getType(Health::class.java))
        assertTrue(module.actions.processorCoordinator.unregisterProcessor(external))

        module.enable()
        assertEquals(1, fixture.scheduler.tasks.size)
        ACTION_KEYS.forEach { assertNotNull(module.actions.processorCoordinator.getProcessor(it)) }
        module.disable()
        ACTION_KEYS.forEach { assertNull(module.actions.processorCoordinator.getProcessor(it)) }
    }

    @Test
    fun `root queue dispatches actions through existing validators using configured limit`() {
        val fixture = RootFixture(maxActionsPerTick = 1)
        val module = fixture.module
        module.registerDefinition(CitadelDefinition(TYPE, emptyList()), DamagePolicy.ALLOW, behavior())
        fixture.load()
        module.enable()
        assertEquals(ActionResult.SUCCESS, module.actions.execute(placeAction()))
        val citadel = assertNotNull(fixture.manager.findCitadel(POSITION.world, POSITION.x, POSITION.y, POSITION.z))
        module.actions.post(RemoveAction(ActionContext.SYSTEM, POSITION))
        module.actions.post(DamageAction(ActionContext.SYSTEM, POSITION, 2.0))

        fixture.scheduler.tick()
        assertTrue(citadel.isCurrent) // The default removal policy still denies the queued request.
        assertEquals(10.0, citadel.getComponent(Health::class.java)?.health)
        fixture.scheduler.tick()
        assertEquals(8.0, citadel.getComponent(Health::class.java)?.health)
    }

    @Test
    fun `root forwards queue failures to the configured handler`() {
        val fixture = RootFixture()
        val failure = IllegalStateException("External validation failed")
        fixture.module.enable()
        fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<PlaceAction> {
            override val actionClass = PlaceAction::class.java
            override fun validate(action: PlaceAction): ActionValidator.Result = throw failure
        })
        fixture.module.actions.post(placeAction())

        fixture.scheduler.tick()
        assertSame(failure, fixture.failures.single())
    }

    @Test
    fun `timer scheduling failure rolls back root handlers and can be retried`() {
        val fixture = RootFixture()
        val failure = IllegalStateException("Scheduler unavailable")
        fixture.scheduler.timerFailure = failure
        fixture.module.actions.post(placeAction())

        assertSame(failure, assertFailsWith<IllegalStateException> { fixture.module.enable() })
        ACTION_KEYS.forEach { assertNull(fixture.module.actions.processorCoordinator.getProcessor(it)) }
        assertEquals(0, fixture.module.actions.drainPosted())
        assertNotNull(fixture.types.getType(Health::class.java))

        fixture.scheduler.timerFailure = null
        fixture.module.enable()
        ACTION_KEYS.forEach { assertNotNull(fixture.module.actions.processorCoordinator.getProcessor(it)) }
        assertEquals(1, fixture.scheduler.tasks.size)
    }

    @Test
    fun `failed timer cancellation still detaches root handlers and clears pending actions`() {
        val fixture = RootFixture()
        fixture.module.enable()
        val failure = IllegalStateException("Cancellation failed")
        fixture.scheduler.tasks.single().cancellationFailure = failure
        fixture.module.actions.post(placeAction())

        assertSame(failure, assertFailsWith<IllegalStateException> { fixture.module.disable() })
        ACTION_KEYS.forEach { assertNull(fixture.module.actions.processorCoordinator.getProcessor(it)) }
        assertEquals(0, fixture.module.actions.drainPosted())
        fixture.module.disable()
        fixture.module.enable()
        assertEquals(2, fixture.scheduler.tasks.size)
    }

    private class Fixture {
        val types = TypeManager()
        val storage = MemoryStorage()
        private val manager = ComponentManager(storage, types)
        val actions = ActionManager(manager)
        private val definitions = CitadelDefinitionRegistry()
        private val lifecycle = CitadelLifecycleNotifications()
        private val place = PlaceService(actions, definitions, lifecycle)
        private val remove = RemoveService(actions, lifecycle, RemovalPolicy { _, _ -> true })
        val health = HealthService(actions, remove)
        private val services: List<LifecycleService> = listOf(remove, health, place)

        fun enable() = services.forEach { it.enable() }
        fun disable() = services.asReversed().forEach { it.disable() }

        fun prepare(policy: DamagePolicy = DamagePolicy.ALLOW) {
            val behavior = behavior()
            definitions.register(CitadelDefinition(TYPE, listOf(HealthInitializer(behavior))))
            health.registerBehavior(TYPE, behavior)
            health.registerPolicy(TYPE, policy)
            storage.chunk = ChunkCord(WXZCord(POSITION.world, 0, 0))
        }

        fun place() = actions.execute(placeAction())
        fun find() = manager.findCitadel(POSITION.world, POSITION.x, POSITION.y, POSITION.z)
        fun currentHealth() = assertNotNull(find()).getComponent(Health::class.java)?.health
    }

    private class RootFixture(maxActionsPerTick: Int = 1024) {
        val types = TypeManager()
        private val storage = MemoryStorage()
        val manager = ComponentManager(storage, types)
        val scheduler = ManualScheduler()
        val failures = mutableListOf<Exception>()
        val module = CitadelModule(manager, scheduler, maxActionsPerTick = maxActionsPerTick,
            actionFailureHandler = failures::add)

        fun load() { storage.chunk = ChunkCord(WXZCord(POSITION.world, 0, 0)) }
    }

    private class MemoryStorage : CitadelStorage {
        var chunk: ChunkCord? = null
        override fun getIfChunkLoaded(location: WXZCord): ChunkCord? = chunk?.takeIf { it.location == location }
        override fun loadChunk(location: WXZCord): Nothing = error("Unexpected chunk load")
        override fun unloadChunk(location: WXZCord): Nothing = error("Unexpected chunk unload")
        override fun updateOpaque(chunk: ChunkCord): Nothing = error("Unexpected opaque update")
        override fun updateOpaque(key: Key): Nothing = error("Unexpected opaque update")
        override fun saveAll(): Nothing = error("Unexpected save")
        override fun closeAndSaveAll(): Nothing = error("Unexpected close")
    }

    private companion object {
        val POSITION = CitadelPosition(UUID(1L, 2L), 1, 64, 2)
        val TYPE = Key("test", "standalone")
        val ACTION_KEYS = listOf(PlaceAction.KEY, DamageAction.KEY, RemoveAction.KEY)

        fun placeAction() = PlaceAction(ActionContext.SYSTEM, POSITION, TYPE)
        fun behavior() = MaturingHealthBehavior(10.0, 20.0, 10_000L,
            Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC))

        fun damageProcessor() = object : ActionProcessor<DamageAction>() {
            override val actionClass = DamageAction::class.java
            override val actionKey = DamageAction.KEY
            override fun execute(action: DamageAction) = ActionResult.SUCCESS
        }
    }
}
