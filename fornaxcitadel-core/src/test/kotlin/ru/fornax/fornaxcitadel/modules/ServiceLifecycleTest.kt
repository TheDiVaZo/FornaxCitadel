package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.addon.CoreAddon
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.ExactPositionResolver
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.scheduler.ManualScheduler
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionMeta
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
import ru.fornax.fornaxcitadel.modules.queue.ActionQueueService
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
    fun `core addon construction registers persisted types and gameplay processors`() {
        val fixture = RootFixture()

        assertNotNull(fixture.types.getType(CitadelKind::class.java))
        assertNotNull(fixture.types.getType(CitadelOwner::class.java))
        assertNotNull(fixture.types.getType(Health::class.java))
        assertNotNull(fixture.types.getType(Maturing::class.java))
        ACTION_KEYS.forEach { assertSlotOccupied(fixture.actions, it) }
        assertTrue(fixture.scheduler.tasks.isEmpty())
    }

    @Test
    fun `registering the same processor without metadata preserves the existing policy`() {
        val manager = ActionManager()
        val actions = ActionFacade(manager)
        val processor = damageProcessor()
        val request = DamageAction(ActionContext.SYSTEM, POSITION, 1.0)

        manager.registerActionMeta(request.type, ActionMeta(false))
        actions.registerAction(processor)
        actions.registerAction(processor)
        assertFalse(actions.isAllowed(request))
        assertEquals(ActionResult.DENIED, actions.execute(request))

        actions.registerAction(processor, ActionMeta(true))
        actions.registerAction(processor)
        assertTrue(actions.isAllowed(request))
        assertEquals(ActionResult.SUCCESS, actions.execute(request))
    }

    @Test
    fun `a conflicting processor cannot replace the owner or change its policy`() {
        val manager = ActionManager()
        val actions = ActionFacade(manager)
        val request = DamageAction(ActionContext.SYSTEM, POSITION, 1.0)
        val owner = damageProcessor()
        val replacement = damageProcessor()
        actions.registerAction(owner, ActionMeta(true))

        assertFailsWith<IllegalArgumentException> { actions.registerAction(replacement) }
        assertFailsWith<IllegalArgumentException> { actions.registerAction(replacement, ActionMeta(false)) }
        assertTrue(actions.isAllowed(request))
        assertEquals(ActionResult.SUCCESS, actions.execute(request))
        assertFalse(manager.unregisterProcessor(replacement))
        assertTrue(manager.unregisterProcessor(owner))
    }

    @Test
    fun `external damage validator can veto an otherwise authorized request`() {
        val fixture = RootFixture()
        fixture.addon.registerDefinition(CitadelDefinition(TYPE, initializers()))
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(placeAction()))
        val damage = DamageAction(ActionContext.SYSTEM, POSITION, 2.0)
        val external = rejectDamage()
        fixture.actions.registerValidator(external)

        assertFalse(fixture.actions.isAllowed(damage))
        assertEquals(ActionResult.DENIED, fixture.actions.execute(damage))
        assertTrue(fixture.actions.unregisterValidator(external))
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(damage))
        assertEquals(8.0, fixture.citadels.findProtecting(POSITION)?.getComponent(Health::class.java)?.health)
    }

    @Test
    fun `restarting the queue preserves definitions and data and dispatches the pending action`() {
        val fixture = RootFixture()
        val definition = CitadelDefinition(TYPE, initializers())
        fixture.addon.registerDefinition(definition)
        val chunk = fixture.load()
        val types = listOf(CitadelKind.KEY, CitadelOwner.KEY, Health.KEY, Maturing.KEY)
            .map { assertNotNull(fixture.types.getType(it)) }
        fixture.queue.enable()
        fixture.queue.enable()
        val firstTimer = fixture.scheduler.tasks.single()
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(placeAction()))
        val original = assertNotNull(fixture.citadels.findProtecting(POSITION))
        val health = assertNotNull(original.getComponent(Health::class.java))
        val maturation = assertNotNull(original.getComponent(Maturing::class.java))
        assertEquals(1_000L, maturation.startedAtMillis)
        fixture.actions.post(DamageAction(ActionContext.SYSTEM, POSITION, 2.0))
        chunk.resetDirty()

        fixture.queue.disable()
        fixture.queue.disable()
        assertTrue(firstTimer.isCancelled)
        assertTrue(original.isCurrent)
        assertSame(health, original.getComponent(Health::class.java))
        assertSame(maturation, original.getComponent(Maturing::class.java))
        assertSame(definition, fixture.addon.definitions.find(TYPE))
        types.forEach { assertSame(it, fixture.types.getType(it.key)) }
        fixture.scheduler.tick()
        assertFalse(chunk.isDirty)

        fixture.queue.enable()
        assertEquals(2, fixture.scheduler.tasks.size)
        fixture.scheduler.tick()
        assertEquals(0, fixture.actions.drainPosted())
        assertEquals(8.0, health.health)
        fixture.actions.validateRemoval(fixture.citadels)
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(RemoveAction(ActionContext.SYSTEM, POSITION)))
        assertFalse(original.isCurrent)
    }

    @Test
    fun `core addon construction rejects an occupied processor key and preserves its owner`() {
        for (key in ACTION_KEYS) {
            val actions = ActionManager()
            val external = probeProcessor(key)
            actions.registerProcessor(external)
            val citadels = CitadelFacade(ComponentManager(MemoryStorage(), TypeManager()), ExactPositionResolver)
            assertFailsWith<IllegalArgumentException> { CoreAddon(citadels, ActionFacade(actions)) }
            assertTrue(actions.unregisterProcessor(external))
        }
    }

    @Test
    fun `core authorization defaults preserve the policies of external registrations`() {
        val actions = ActionManager()
        val citadels = CitadelFacade(ComponentManager(MemoryStorage(), TypeManager()), ExactPositionResolver)
        actions.registerActionMeta(RepairAction.KEY, ActionMeta(true))
        actions.registerActionMeta(UpgradeAction.KEY, ActionMeta(false))
        CoreAddon(citadels, ActionFacade(actions))

        val protectedKeys = listOf(RemoveAction.KEY, DamageAction.KEY, DismantleAction.KEY, RepairAction.KEY, UpgradeAction.KEY)
        for (key in protectedKeys) {
            assertEquals(key == RepairAction.KEY, actions.isAllowed(Action(key, POSITION)), key.toString())
        }
    }

    @Test
    fun `maturation precedes external damage modifiers from addon construction`() {
        val fixture = RootFixture()
        fixture.load()
        val health = fixture.addon.health
        val citadel = assertNotNull(fixture.citadels.createCitadel(POSITION) { draft ->
            draft.addComponent(CitadelKind::class.java, CitadelKind(TYPE))
            draft.addComponent(Health::class.java, Health(100.0))
            draft.addComponent(Maturing::class.java, Maturing(Long.MAX_VALUE))
        })
        val seen = mutableListOf<Double>()
        health.registerModifier(DamageModifier { _, _, damage ->
            seen.add(damage)
            damage
        })
        assertEquals(ActionResult.SUCCESS, health.damage(citadel, 2.0))
        assertEquals(listOf(4.0), seen)
    }

    @Test
    fun `queue dispatches third party work alongside core processors`() {
        val fixture = RootFixture()
        val key = Key("external", "probe")
        val intent = Action(key, POSITION, ActionContext.SYSTEM)
        val executed = mutableListOf<Action>()
        fixture.actions.registerProcessor(object : ActionProcessor<Action>() {
            override val actionClass = Action::class.java
            override val actionKey = key
            override fun execute(action: Action): ActionResult {
                executed.add(action)
                return ActionResult.SUCCESS
            }
        })
        fixture.queue.enable()
        fixture.actions.post(intent)

        assertFalse(fixture.scheduler.tasks.single().isCancelled)
        assertTrue(executed.isEmpty())
        fixture.scheduler.tick()
        assertEquals(listOf(intent), executed)
        assertEquals(0, fixture.actions.drainPosted())
    }

    @Test
    fun `queue dispatches actions through existing validators using configured limit`() {
        val fixture = RootFixture(maxActionsPerTick = 1)
        fixture.addon.registerDefinition(CitadelDefinition(TYPE, initializers()))
        fixture.load()
        fixture.queue.enable()
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(placeAction()))
        val citadel = assertNotNull(fixture.manager.findCitadel(POSITION.world, POSITION.x, POSITION.y, POSITION.z))
        fixture.actions.post(RemoveAction(ActionContext.SYSTEM, POSITION))
        fixture.actions.post(DamageAction(ActionContext.SYSTEM, POSITION, 2.0))

        fixture.scheduler.tick()
        assertTrue(citadel.isCurrent) // No validator approves the queued removal request.
        assertEquals(10.0, citadel.getComponent(Health::class.java)?.health)
        fixture.scheduler.tick()
        assertEquals(8.0, citadel.getComponent(Health::class.java)?.health)
    }

    @Test
    fun `queue forwards failures to the configured handler`() {
        val fixture = RootFixture()
        fixture.addon.registerDefinition(CitadelDefinition(TYPE, initializers()))
        fixture.load()
        val failure = IllegalStateException("External validation failed")
        fixture.queue.enable()
        fixture.actions.registerValidator(object : ActionValidator<PlaceAction> {
            override val actionClass = PlaceAction::class.java
            override fun validate(action: PlaceAction): ActionValidator.Result = throw failure
        })
        fixture.actions.post(placeAction())

        fixture.scheduler.tick()
        assertSame(failure, fixture.failures.single())
    }

    private class RootFixture(maxActionsPerTick: Int = 1024) {
        val types = TypeManager()
        private val storage = MemoryStorage()
        val manager = ComponentManager(storage, types)
        val scheduler = ManualScheduler()
        val failures = mutableListOf<Exception>()
        val actions = ActionManager()
        val citadels = CitadelFacade(manager, ExactPositionResolver)
        private val actionFacade = ActionFacade(actions)
        val addon = CoreAddon(citadels, actionFacade)
        val queue = ActionQueueService(actionFacade, scheduler, ActionSettings(maxActionsPerTick, failures::add))

        init {
            actions.validateDamage(citadels, TYPE)
            addon.health.maturation.registerDefinition(TYPE, MaturationDefinition(Duration.ofSeconds(10), 2.0))
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

        fun placeAction() = PlaceAction(ActionContext.SYSTEM, POSITION, TYPE)
        fun initializers(): List<CitadelInitializer> = listOf(
            CitadelInitializer { _, draft -> draft.addComponent(Health::class.java, Health(10.0)) },
            MaturingInitializer(Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC))
        )

        fun rejectDamage() = object : ActionValidator<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun validate(action: DamageAction) = ActionValidator.Result.Rejected
        }

        fun probeProcessor(key: Key) = object : ActionProcessor<Action>() {
            override val actionClass = Action::class.java
            override val actionKey = key
            override fun execute(action: Action) = ActionResult.SUCCESS
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
