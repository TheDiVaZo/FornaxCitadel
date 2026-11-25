package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.scheduler.ManualScheduler
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionMeta
import ru.fornax.fornaxcitadel.core.systems.action.ActionRegistration
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.DamageModifier
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.health.MaturingInitializer
import ru.fornax.fornaxcitadel.modules.health.MaturationDefinition
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.queue.ActionSettings
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServiceLifecycleTest {
    @Test
    fun `module registers core types through the supplied component manager before enable`() {
        val types = TypeManager()
        val manager = ComponentManager(MemoryStorage(), types)
        val module = CitadelModule(manager, ManualScheduler())

        assertSame(manager, module.componentManager)
        assertNotNull(types.getType(CitadelKind::class.java))
        assertNotNull(types.getType(CitadelOwner::class.java))
        assertNotNull(types.getType(Health::class.java))
        assertNotNull(types.getType(Maturing::class.java))
        ACTION_KEYS.forEach { assertSlotAvailable(module.actions, it) }
    }

    @Test
    fun `repeated enable restores the owned processor and preserves the prerequisite`() {
        val actions = newActionManager()
        val own = damageProcessor()
        val registration = ActionRegistration(actions, own, listOf(rejectDamage()))
        val request = DamageAction(ActionContext.SYSTEM, POSITION, 1.0)
        registration.enable()

        assertTrue(actions.unregisterProcessor(own))
        assertFalse(actions.isAllowed(request))
        registration.enable()
        assertFalse(actions.isAllowed(request))
        assertEquals(ActionResult.DENIED, actions.execute(request))
        assertTrue(actions.unregisterProcessor(own))

        registration.disable()
        assertTrue(actions.isAllowed(request))
        assertSlotAvailable(actions, request.type)
    }

    @Test
    fun `repeated enable rejects a replacement without losing prerequisites or removing it on disable`() {
        val actions = newActionManager()
        val own = damageProcessor()
        val registration = ActionRegistration(actions, own, listOf(rejectDamage()))
        val request = DamageAction(ActionContext.SYSTEM, POSITION, 1.0)
        registration.enable()
        assertTrue(actions.unregisterProcessor(own))
        var externalExecutions = 0
        val replacement = object : ActionProcessor<DamageAction>() {
            override val actionClass = DamageAction::class.java
            override val actionKey = DamageAction.KEY
            override fun execute(action: DamageAction): ActionResult {
                externalExecutions++
                return ActionResult.SUCCESS
            }
        }
        actions.registerProcessor(replacement)

        assertFailsWith<IllegalArgumentException> { registration.enable() }
        assertFalse(actions.isAllowed(request))
        assertEquals(ActionResult.DENIED, actions.execute(request))
        assertEquals(0, externalExecutions)

        registration.disable()
        assertTrue(actions.isAllowed(request))
        assertFalse(actions.unregisterProcessor(own))
        assertEquals(ActionResult.SUCCESS, actions.execute(request))
        assertEquals(1, externalExecutions)
        assertTrue(actions.unregisterProcessor(replacement))
    }

    @Test
    fun `external damage validator still vetoes actions after restarting the core addon`() {
        val fixture = RootFixture()
        val module = fixture.module
        module.registerDefinition(CitadelDefinition(TYPE, initializers()))
        fixture.load()
        module.coreAddon.onEnable()
        assertEquals(ActionResult.SUCCESS, module.actions.execute(placeAction()))
        val damage = DamageAction(ActionContext.SYSTEM, POSITION, 2.0)
        val external = rejectDamage()
        module.actions.registerValidator(external)

        assertEquals(ActionResult.DENIED, module.actions.execute(damage))
        module.coreAddon.onDisable()
        assertFalse(module.actions.isAllowed(damage))
        module.coreAddon.onEnable()
        assertEquals(ActionResult.DENIED, module.actions.execute(damage))
        assertTrue(module.actions.unregisterValidator(external))
        assertEquals(ActionResult.SUCCESS, module.actions.execute(damage))
        assertEquals(8.0, module.citadels.findProtecting(POSITION)?.getComponent(Health::class.java)?.health)
    }

    @Test
    fun `root lifecycle preserves definitions and data but discards pending actions`() {
        val fixture = RootFixture()
        val module = fixture.module
        val definition = CitadelDefinition(TYPE, initializers())
        module.registerDefinition(definition)
        val chunk = fixture.load()
        val types = listOf(CitadelKind.KEY, CitadelOwner.KEY, Health.KEY, Maturing.KEY)
            .map { assertNotNull(fixture.types.getType(it)) }
        assertEquals(ActionResult.DENIED, module.actions.execute(placeAction()))
        assertTrue(fixture.scheduler.tasks.isEmpty())
        module.coreAddon.onEnable()
        module.enable()
        module.enable()
        val firstTimer = fixture.scheduler.tasks.single()
        assertEquals(ActionResult.SUCCESS, module.actions.execute(placeAction()))
        val original = assertNotNull(fixture.manager.findCitadel(POSITION.world, POSITION.x, POSITION.y, POSITION.z))
        val health = assertNotNull(original.getComponent(Health::class.java))
        val maturation = assertNotNull(original.getComponent(Maturing::class.java))
        assertEquals(1_000L, maturation.startedAtMillis)
        module.actions.post(DamageAction(ActionContext.SYSTEM, POSITION, 100.0))
        chunk.resetDirty()

        module.disable()
        module.disable()
        module.coreAddon.onDisable()
        module.coreAddon.onDisable()
        assertTrue(firstTimer.isCancelled)
        ACTION_KEYS.forEach { assertSlotAvailable(module.actions, it) }
        assertTrue(original.isCurrent)
        assertSame(health, original.getComponent(Health::class.java))
        assertSame(maturation, original.getComponent(Maturing::class.java))
        assertSame(definition, module.definitions.find(TYPE))
        module.coreAddon.onEnable()
        module.enable()
        types.forEach { assertSame(it, fixture.types.getType(it.key)) }
        assertFalse(chunk.isDirty)
        assertEquals(2, fixture.scheduler.tasks.size)
        fixture.scheduler.tick()
        assertEquals(0, module.actions.drainPosted())
        assertEquals(ActionResult.SUCCESS, module.actions.execute(DamageAction(ActionContext.SYSTEM, POSITION, 2.0)))
        assertEquals(8.0, original.getComponent(Health::class.java)?.health)
        module.actions.validateRemoval(module.citadels)
        assertEquals(ActionResult.SUCCESS, module.actions.execute(RemoveAction(ActionContext.SYSTEM, POSITION)))
        assertFalse(original.isCurrent)
    }

    @Test
    fun `a conflict on any core action rolls back the addon and preserves external registrations`() {
        val requests = listOf(placeAction(), DamageAction(ActionContext.SYSTEM, POSITION, 1.0),
            RemoveAction(ActionContext.SYSTEM, POSITION))
        for (request in requests) {
            val fixture = RootFixture()
            fixture.load()
            val module = fixture.module
            val external = probeProcessor(request.type)
            module.actions.registerProcessor(external)
            module.actions.registerValidator(object : ActionValidator<ActionData> {
                override val actionClass = ActionData::class.java
                override fun validate(action: ActionData) = ActionValidator.Result.Success
            })

            assertFailsWith<IllegalArgumentException> { module.coreAddon.onEnable() }
            assertTrue(fixture.scheduler.tasks.isEmpty())
            for (key in ACTION_KEYS - request.type) assertSlotAvailable(module.actions, key)
            requests.forEach { assertTrue(module.actions.isAllowed(it)) }
            assertEquals(ActionResult.SUCCESS, module.actions.execute(request))
            assertTrue(module.actions.unregisterProcessor(external))

            module.coreAddon.onEnable()
            assertTrue(fixture.scheduler.tasks.isEmpty())
            ACTION_KEYS.forEach { assertSlotOccupied(module.actions, it) }
            requests.forEach { assertFalse(module.actions.isAllowed(it)) }
            module.coreAddon.onDisable()
            ACTION_KEYS.forEach { assertSlotAvailable(module.actions, it) }
        }
    }

    @Test
    fun `core metadata fills free keys and preserves external policies through rollback and stop`() {
        val module = RootFixture().module
        val keys = listOf(RemoveAction.KEY, DamageAction.KEY, DismantleAction.KEY, RepairAction.KEY, UpgradeAction.KEY)
        fun allowed(key: Key) = module.actions.isAllowed(ActionData(key, POSITION, ActionContext.SYSTEM))
        keys.forEach { assertTrue(allowed(it), it.toString()) }
        module.actions.registerActionMeta(RepairAction.KEY, ActionMeta(true))
        module.actions.registerActionMeta(UpgradeAction.KEY, ActionMeta(false))

        val conflict = probeProcessor(PlaceAction.KEY)
        module.actions.registerProcessor(conflict)
        assertFailsWith<IllegalArgumentException> { module.coreAddon.onEnable() }
        keys.forEach { assertEquals(it != UpgradeAction.KEY, allowed(it), it.toString()) }
        assertTrue(module.actions.unregisterProcessor(conflict))

        repeat(2) { module.coreAddon.onEnable() }
        keys.forEach { assertEquals(it == RepairAction.KEY, allowed(it), it.toString()) }
        // This replacement compares equal to the addon's policy, but belongs to another caller.
        module.actions.registerActionMeta(DamageAction.KEY, ActionMeta(false))
        repeat(2) { module.coreAddon.onDisable() }
        keys.forEach { assertEquals(it != DamageAction.KEY && it != UpgradeAction.KEY, allowed(it), it.toString()) }

        module.coreAddon.onEnable()
        assertTrue(allowed(RepairAction.KEY))
        module.coreAddon.onDisable()
        assertFalse(allowed(DamageAction.KEY))
        assertFalse(allowed(UpgradeAction.KEY))
    }

    @Test
    fun `maturation precedes external damage modifiers only while the core addon is enabled`() {
        val fixture = RootFixture()
        fixture.load()
        val module = fixture.module
        val health = module.coreAddon.health
        val citadel = assertNotNull(module.citadels.createCitadel(POSITION) { draft ->
            draft.addComponent(CitadelKind::class.java, CitadelKind(TYPE))
            draft.addComponent(Health::class.java, Health(100.0))
            draft.addComponent(Maturing::class.java, Maturing(Long.MAX_VALUE))
        })
        val seen = mutableListOf<Double>()
        health.registerModifier(DamageModifier { _, _, damage ->
            seen.add(damage)
            damage
        })
        fun hit() = assertEquals(ActionResult.SUCCESS, health.damage(citadel, 2.0))

        hit()
        val conflict = probeProcessor(PlaceAction.KEY)
        module.actions.registerProcessor(conflict)
        assertFailsWith<IllegalArgumentException> { module.coreAddon.onEnable() }
        hit()
        assertTrue(module.actions.unregisterProcessor(conflict))
        repeat(2) { module.coreAddon.onEnable() }
        hit()
        repeat(2) { module.coreAddon.onDisable() }
        hit()
        module.coreAddon.onEnable()
        hit()
        assertEquals(listOf(2.0, 2.0, 4.0, 2.0, 4.0), seen)
    }

    @Test
    fun `stopping the core addon leaves queued third party work for the next tick`() {
        val fixture = RootFixture()
        val module = fixture.module
        val key = Key("external", "probe")
        val intent = ActionData(key, POSITION, ActionContext.SYSTEM)
        val executed = mutableListOf<ActionData>()
        module.actions.registerProcessor(object : ActionProcessor<ActionData>() {
            override val actionClass = ActionData::class.java
            override val actionKey = key
            override fun execute(action: ActionData): ActionResult {
                executed.add(action)
                return ActionResult.SUCCESS
            }
        })
        module.coreAddon.onEnable()
        module.enable()
        module.actions.post(intent)
        module.coreAddon.onDisable()

        assertFalse(fixture.scheduler.tasks.single().isCancelled)
        assertTrue(executed.isEmpty())
        fixture.scheduler.tick()
        assertEquals(listOf(intent), executed)
        assertEquals(0, module.actions.drainPosted())
    }

    @Test
    fun `root queue dispatches actions through existing validators using configured limit`() {
        val fixture = RootFixture(maxActionsPerTick = 1)
        val module = fixture.module
        module.registerDefinition(CitadelDefinition(TYPE, initializers()))
        fixture.load()
        module.coreAddon.onEnable()
        module.enable()
        assertEquals(ActionResult.SUCCESS, module.actions.execute(placeAction()))
        val citadel = assertNotNull(fixture.manager.findCitadel(POSITION.world, POSITION.x, POSITION.y, POSITION.z))
        module.actions.post(RemoveAction(ActionContext.SYSTEM, POSITION))
        module.actions.post(DamageAction(ActionContext.SYSTEM, POSITION, 2.0))

        fixture.scheduler.tick()
        assertTrue(citadel.isCurrent) // No validator approves the queued removal request.
        assertEquals(10.0, citadel.getComponent(Health::class.java)?.health)
        fixture.scheduler.tick()
        assertEquals(8.0, citadel.getComponent(Health::class.java)?.health)
    }

    @Test
    fun `root forwards queue failures to the configured handler`() {
        val fixture = RootFixture()
        fixture.module.registerDefinition(CitadelDefinition(TYPE, initializers()))
        fixture.load()
        val failure = IllegalStateException("External validation failed")
        fixture.module.enable()
        fixture.module.actions.registerValidator(object : ActionValidator<PlaceAction> {
            override val actionClass = PlaceAction::class.java
            override fun validate(action: PlaceAction): ActionValidator.Result = throw failure
        })
        fixture.module.actions.post(placeAction())

        fixture.scheduler.tick()
        assertSame(failure, fixture.failures.single())
    }

    @Test
    fun `timer scheduling failure clears the queue and preserves active addon registrations`() {
        val fixture = RootFixture()
        fixture.module.coreAddon.onEnable()
        val failure = IllegalStateException("Scheduler unavailable")
        fixture.scheduler.timerFailure = failure
        fixture.module.actions.post(placeAction())

        assertSame(failure, assertFailsWith<IllegalStateException> { fixture.module.enable() })
        ACTION_KEYS.forEach { assertSlotOccupied(fixture.module.actions, it) }
        assertEquals(0, fixture.module.actions.drainPosted())
        assertNotNull(fixture.types.getType(Health::class.java))

        fixture.scheduler.timerFailure = null
        fixture.module.enable()
        ACTION_KEYS.forEach { assertSlotOccupied(fixture.module.actions, it) }
        assertEquals(1, fixture.scheduler.tasks.size)
    }

    @Test
    fun `failed timer cancellation clears pending actions and leaves the addon active`() {
        val fixture = RootFixture()
        fixture.module.coreAddon.onEnable()
        fixture.module.enable()
        val failure = IllegalStateException("Cancellation failed")
        fixture.scheduler.tasks.single().cancellationFailure = failure
        fixture.module.actions.post(placeAction())

        assertSame(failure, assertFailsWith<IllegalStateException> { fixture.module.disable() })
        ACTION_KEYS.forEach { assertSlotOccupied(fixture.module.actions, it) }
        assertEquals(0, fixture.module.actions.drainPosted())
        fixture.module.disable()
        fixture.module.enable()
        assertEquals(2, fixture.scheduler.tasks.size)
    }

    private class RootFixture(maxActionsPerTick: Int = 1024) {
        val types = TypeManager()
        private val storage = MemoryStorage()
        val manager = ComponentManager(storage, types)
        val scheduler = ManualScheduler()
        val failures = mutableListOf<Exception>()
        val module = CitadelModule(manager, scheduler,
            actionSettings = ActionSettings(maxActionsPerTick, failures::add)).apply {
            actions.validateDamage(citadels, TYPE)
            coreAddon.health.maturation.registerDefinition(TYPE, MaturationDefinition(Duration.ofSeconds(10), 2.0))
        }

        fun load() = ChunkCord(WXZCord(POSITION.world, 0, 0)).also { storage.chunk = it }
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

        fun newActionManager() = ActionManager(CitadelFacade(ComponentManager(MemoryStorage(), TypeManager())))

        fun placeAction() = PlaceAction(ActionContext.SYSTEM, POSITION, TYPE)
        fun initializers(): List<CitadelInitializer> = listOf(
            CitadelInitializer { _, draft -> draft.addComponent(Health::class.java, Health(10.0)) },
            MaturingInitializer(Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC))
        )

        fun rejectDamage() = object : ActionValidator<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun validate(action: DamageAction) = ActionValidator.Result.Rejected
        }

        fun probeProcessor(key: Key) = object : ActionProcessor<ActionData>() {
            override val actionClass = ActionData::class.java
            override val actionKey = key
            override fun execute(action: ActionData) = ActionResult.SUCCESS
        }

        fun assertSlotAvailable(actions: ActionManager, key: Key) {
            val probe = probeProcessor(key)
            actions.registerProcessor(probe)
            assertTrue(actions.unregisterProcessor(probe))
        }

        fun assertSlotOccupied(actions: ActionManager, key: Key) {
            assertFailsWith<IllegalArgumentException> { actions.registerProcessor(probeProcessor(key)) }
        }

        fun damageProcessor() = object : ActionProcessor<DamageAction>() {
            override val actionClass = DamageAction::class.java
            override val actionKey = DamageAction.KEY
            override fun execute(action: DamageAction) = ActionResult.SUCCESS
        }
    }
}
