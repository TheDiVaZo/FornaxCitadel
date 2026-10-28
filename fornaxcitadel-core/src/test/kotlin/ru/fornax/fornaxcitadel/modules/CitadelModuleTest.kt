package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleListener
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.DBStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.breaking.BreakAction
import ru.fornax.fornaxcitadel.modules.breaking.BreakPolicy
import ru.fornax.fornaxcitadel.modules.breaking.UnbreakablePolicy
import ru.fornax.fornaxcitadel.modules.fortification.Fortification
import ru.fornax.fornaxcitadel.modules.fortification.FortificationInitializer
import ru.fornax.fornaxcitadel.modules.fortification.FortifiedHealthBehavior
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthBehavior
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.health.MaturingHealthBehavior
import ru.fornax.fornaxcitadel.modules.health.SimpleHealthBehavior
import ru.fornax.fornaxcitadel.modules.ownership.Owner
import ru.fornax.fornaxcitadel.modules.ownership.OwnerInitializer
import ru.fornax.fornaxcitadel.modules.ownership.OwnerOrDamagePolicy
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemovalPolicy
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CitadelModuleTest {
    @Test
    fun `placement publishes a complete citadel and occupied placement leaves it unchanged`() {
        val fixture = Fixture()
        val clock = MutableClock(1_793_204_123_456L)
        fixture.register(behavior = MaturingHealthBehavior(20.0, 100.0, 1_000L, clock))
        fixture.load()
        var observedOwner: UUID? = null
        var observedKind: Key? = null
        var observedHealth: Double? = null
        var observedStart: Long? = null
        fixture.module.lifecycle.addListener(object : CitadelLifecycleListener {
            override fun onPlaced(position: CitadelPosition, runtimeId: Int) {
                val handle = fixture.find(position) ?: return
                observedOwner = handle.getComponent(Owner::class.java)?.playerId
                observedKind = handle.getComponent(CitadelKind::class.java)?.definitionKey
                observedHealth = handle.getComponent(Health::class.java)?.health
                observedStart = handle.getComponent(Maturing::class.java)?.startedAtMillis
            }
        })

        assertEquals(ActionResult.SUCCESS, fixture.place())

        assertEquals(OWNER, observedOwner)
        assertEquals(DEFAULT_KIND, observedKind)
        assertEquals(20.0, observedHealth)
        assertEquals(clock.timeMillis, observedStart)
        assertEquals(listOf(POSITION), fixture.placements)
        fixture.chunk().resetDirty()

        assertEquals(ActionResult.DENIED, fixture.place(context = STRANGER_CONTEXT))
        assertEquals(OWNER, fixture.handle().getComponent(Owner::class.java)?.playerId)
        assertEquals(1, fixture.placements.size)
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `vetoed placement leaves no entity components or notification`() {
        val fixture = Fixture()
        fixture.register()
        fixture.load()
        fixture.reject(PlaceAction::class.java)

        assertEquals(ActionResult.DENIED, fixture.place())

        assertNull(fixture.find())
        assertTrue(fixture.chunk().isEmpty)
        assertFalse(fixture.chunk().isDirty)
        assertTrue(fixture.placements.isEmpty())
    }

    @Test
    fun `initializer failure discards the entire unpublished draft`() {
        val fixture = Fixture()
        fixture.register(initializers = listOf(CitadelInitializer { _, draft ->
            draft.addComponent(Maturing::class.java, Maturing(123L))
            error("Initialization failed after preparing a component")
        }))
        fixture.load()

        assertFailsWith<IllegalStateException> { fixture.place() }

        assertNull(fixture.find())
        assertTrue(fixture.chunk().isEmpty)
        assertFalse(fixture.chunk().isDirty)
        assertTrue(fixture.placements.isEmpty())
    }

    @Test
    fun `owner dismantles immediately only after global validation allows the action`() {
        val fixture = Fixture()
        fixture.register()
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())

        assertEquals(ActionResult.SUCCESS, fixture.breakCitadel(OWNER_CONTEXT, 1.0))

        assertNull(fixture.find())
        assertEquals(listOf(RemovalCause.OWNER_DISMANTLE), fixture.removals.map { it.second })
        assertEquals(listOf(false), fixture.removalSawExistingEntity)

        val protected = Fixture()
        protected.register()
        protected.load()
        assertEquals(ActionResult.SUCCESS, protected.place())
        protected.chunk().resetDirty()
        protected.reject(BreakAction::class.java)

        assertEquals(ActionResult.DENIED, protected.breakCitadel(OWNER_CONTEXT, 1.0))
        assertEquals(100.0, protected.health().health)
        assertFalse(protected.chunk().isDirty)
        assertTrue(protected.removals.isEmpty())
    }

    @Test
    fun `outsider damages health and death removes once without running removal vetoes`() {
        val fixture = Fixture()
        fixture.register()
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        var removalValidationCalls = 0
        fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<RemoveAction> {
            override val actionClass = RemoveAction::class.java
            override fun validate(action: RemoveAction): ActionValidator.Result {
                removalValidationCalls++
                return ActionValidator.Result.Rejected
            }
        })

        assertEquals(ActionResult.SUCCESS, fixture.breakCitadel(STRANGER_CONTEXT, 25.0))
        assertEquals(75.0, fixture.health().health)
        assertTrue(fixture.removals.isEmpty())
        assertEquals(ActionResult.SUCCESS, fixture.breakCitadel(STRANGER_CONTEXT, 75.0))

        assertNull(fixture.find())
        assertEquals(0, removalValidationCalls)
        assertEquals(listOf(RemovalCause.DESTROYED), fixture.removals.map { it.second })
        assertEquals(ActionResult.DENIED, fixture.breakCitadel(STRANGER_CONTEXT, 1.0))
        assertEquals(1, fixture.removals.size)
    }

    @Test
    fun `invulnerable type rejects both break attempts and direct damage even with a health component`() {
        val fixture = Fixture()
        fixture.register(policy = UnbreakablePolicy)
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        fixture.chunk().resetDirty()

        assertEquals(ActionResult.DENIED, fixture.breakCitadel(OWNER_CONTEXT, 1_000.0))
        assertEquals(ActionResult.DENIED, fixture.breakCitadel(STRANGER_CONTEXT, 1_000.0))
        assertEquals(
            ActionResult.DENIED,
            fixture.module.actions.execute(DamageAction(STRANGER_CONTEXT, POSITION, 1_000.0))
        )

        assertEquals(100.0, fixture.health().health)
        assertFalse(fixture.chunk().isDirty)
        assertTrue(fixture.removals.isEmpty())
    }

    @Test
    fun `invulnerable definition needs no artificial health component`() {
        val fixture = Fixture()
        fixture.register(behavior = null, policy = UnbreakablePolicy)
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())

        assertNull(fixture.handle().getComponent(Health::class.java))
        assertNull(fixture.module.health.getCurrentHealth(WORLD, POSITION.x, POSITION.y, POSITION.z))
        assertEquals(ActionResult.DENIED, fixture.breakCitadel(OWNER_CONTEXT, 100.0))
        assertEquals(
            ActionResult.DENIED,
            fixture.module.actions.execute(DamageAction(STRANGER_CONTEXT, POSITION, 100.0))
        )
        assertNotNull(fixture.find())
        assertTrue(fixture.removals.isEmpty())
    }

    @Test
    fun `fortified definition initializes from its level and later changes preserve the damaged fraction`() {
        val fixture = Fixture()
        fixture.register(
            behavior = FortifiedHealthBehavior(listOf(100.0, 200.0, 400.0)),
            initializers = listOf(OwnerInitializer(), FortificationInitializer(2))
        )
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        assertEquals(2, fixture.handle().getComponent(Fortification::class.java)?.level)
        assertEquals(200.0, fixture.health().maxHealth)
        assertEquals(200.0, fixture.health().health)
        assertEquals(ActionResult.SUCCESS, fixture.breakCitadel(STRANGER_CONTEXT, 50.0))
        assertEquals(150.0, fixture.health().health)

        assertTrue(fixture.handle().editComponent(Fortification::class.java) { it.level = 3 })
        fixture.chunk().resetDirty()

        assertEquals(300.0, fixture.module.health.getCurrentHealth(WORLD, POSITION.x, POSITION.y, POSITION.z))
        assertEquals(400.0, fixture.health().maxHealth)
        assertEquals(3, fixture.handle().getComponent(Fortification::class.java)?.level)
        assertTrue(fixture.chunk().isDirty)
    }

    @Test
    fun `unloaded absent unknown occupied and unhandled actions are denied`() {
        val fixture = Fixture()
        fixture.register()

        assertEquals(ActionResult.DENIED, fixture.place())
        assertEquals(ActionResult.DENIED, fixture.breakCitadel(STRANGER_CONTEXT, 1.0))
        fixture.load()
        assertEquals(ActionResult.DENIED, fixture.breakCitadel(STRANGER_CONTEXT, 1.0))
        assertEquals(ActionResult.DENIED, fixture.place(key = Key("test", "missing")))
        assertEquals(ActionResult.SUCCESS, fixture.place())
        assertEquals(ActionResult.DENIED, fixture.place())
        assertEquals(
            ActionResult.DENIED,
            fixture.module.actions.execute(ActionData(Key("test", "unhandled"), WORLD, 1, 64, 2))
        )
    }

    @Test
    fun `one module routes health updates to the behavior selected by each stored type`() {
        val fixture = Fixture()
        val clock = MutableClock(1_000L)
        fixture.register(behavior = SimpleHealthBehavior(25.0))
        fixture.register(MATURING_KIND, MaturingHealthBehavior(20.0, 100.0, 1_000L, clock))
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        assertEquals(ActionResult.SUCCESS, fixture.place(SECOND_POSITION, MATURING_KIND))

        clock.timeMillis = 1_500L

        assertEquals(25.0, fixture.module.health.getCurrentMaxHealth(WORLD, POSITION.x, POSITION.y, POSITION.z))
        assertEquals(60.0, fixture.module.health.getCurrentMaxHealth(WORLD, SECOND_POSITION.x, SECOND_POSITION.y, SECOND_POSITION.z))
        assertEquals(ActionResult.SUCCESS, fixture.breakCitadel(STRANGER_CONTEXT, 5.0))
        assertEquals(ActionResult.SUCCESS, fixture.breakCitadel(STRANGER_CONTEXT, 10.0, SECOND_POSITION))
        assertEquals(20.0, fixture.health().health)
        assertEquals(50.0, fixture.health(SECOND_POSITION).health)
    }

    @Test
    fun `administrative removal requires explicit authorization and source names grant no privilege`() {
        val fixture = Fixture(RemovalPolicy { context, _ -> context.actorId == ADMIN })
        fixture.register()
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        val forgedSource = ActionContext(STRANGER, Key("test", "admin"))

        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(RemoveAction(forgedSource, POSITION)))
        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(RemoveAction(OWNER_CONTEXT, POSITION)))
        assertNotNull(fixture.find())
        assertEquals(
            ActionResult.SUCCESS,
            fixture.module.actions.execute(RemoveAction(ActionContext(ADMIN, PLAYER_SOURCE), POSITION))
        )

        assertNull(fixture.find())
        assertEquals(listOf(RemovalCause.ADMIN), fixture.removals.map { it.second })
    }

    @Test
    fun `administrative removal is denied by default`() {
        val fixture = Fixture()
        fixture.register()
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())

        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(RemoveAction(ActionContext.SYSTEM, POSITION)))
        assertNotNull(fixture.find())
    }

    @Test
    fun `observer failures do not undo committed actions or skip subsequent observers`() {
        val fixture = Fixture()
        fixture.register()
        fixture.load()
        var laterPlaced = 0
        var laterRemoved = 0
        fixture.module.lifecycle.addListener(object : CitadelLifecycleListener {
            override fun onPlaced(position: CitadelPosition, runtimeId: Int) = error("Broken placement observer")
            override fun onRemoved(position: CitadelPosition, runtimeId: Int, cause: RemovalCause) = error("Broken removal observer")
        })
        fixture.module.lifecycle.addListener(object : CitadelLifecycleListener {
            override fun onPlaced(position: CitadelPosition, runtimeId: Int) { laterPlaced++ }
            override fun onRemoved(position: CitadelPosition, runtimeId: Int, cause: RemovalCause) { laterRemoved++ }
        })

        assertEquals(ActionResult.SUCCESS, fixture.place())
        assertNotNull(fixture.find())
        assertEquals(ActionResult.SUCCESS, fixture.breakCitadel(OWNER_CONTEXT, 1.0))

        assertNull(fixture.find())
        assertEquals(1, laterPlaced)
        assertEquals(1, laterRemoved)
    }

    @Test
    fun `observers can post removal but cannot mutate or recursively execute before other observers finish`() {
        val fixture = Fixture()
        fixture.register()
        fixture.load()
        var directRemovalFailure: IllegalStateException? = null
        var nestedExecutionFailure: IllegalStateException? = null
        var nestedDrainFailure: IllegalStateException? = null
        var laterObserverSawEntity = false
        fixture.module.lifecycle.addListener(object : CitadelLifecycleListener {
            override fun onPlaced(position: CitadelPosition, runtimeId: Int) {
                try {
                    fixture.module.remove.remove(fixture.handle(position), RemovalCause.DESTROYED)
                } catch (failure: IllegalStateException) {
                    directRemovalFailure = failure
                }
                try {
                    fixture.module.actions.execute(BreakAction(OWNER_CONTEXT, position, 1.0))
                } catch (failure: IllegalStateException) {
                    nestedExecutionFailure = failure
                }
                try {
                    fixture.module.actions.drainPosted()
                } catch (failure: IllegalStateException) {
                    nestedDrainFailure = failure
                }
                fixture.module.actions.post(BreakAction(OWNER_CONTEXT, position, 1.0))
            }
        })
        fixture.module.lifecycle.addListener(object : CitadelLifecycleListener {
            override fun onPlaced(position: CitadelPosition, runtimeId: Int) {
                laterObserverSawEntity = fixture.find(position) != null
            }
        })

        assertEquals(ActionResult.SUCCESS, fixture.place())

        assertNotNull(directRemovalFailure)
        assertNotNull(nestedExecutionFailure)
        assertNotNull(nestedDrainFailure)
        assertTrue(laterObserverSawEntity)
        assertNotNull(fixture.find())
        assertTrue(fixture.removals.isEmpty())
        assertEquals(1, fixture.module.actions.drainPosted())
        assertNull(fixture.find())
        assertEquals(listOf(RemovalCause.OWNER_DISMANTLE), fixture.removals.map { it.second })
        assertEquals(0, fixture.module.actions.drainPosted())
    }

    @Test
    fun `posted actions preserve fifo order and draining respects its limit`() {
        val fixture = Fixture()
        val executed = mutableListOf<Int>()
        fixture.registerProbe { action ->
            executed.add(action.number)
            if (action.number == 1) fixture.module.actions.post(ProbeAction(3))
            ActionResult.SUCCESS
        }
        fixture.module.actions.post(ProbeAction(1))
        fixture.module.actions.post(ProbeAction(2))

        assertEquals(0, fixture.module.actions.drainPosted(0))
        assertTrue(executed.isEmpty())
        assertFailsWith<IllegalArgumentException> { fixture.module.actions.drainPosted(-1) }
        assertEquals(1, fixture.module.actions.drainPosted(1))
        assertEquals(listOf(1), executed)

        assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(ProbeAction(4)))
        assertEquals(listOf(1, 4), executed)
        assertEquals(2, fixture.module.actions.drainPosted())
        assertEquals(listOf(1, 4, 2, 3), executed)
        assertEquals(0, fixture.module.actions.drainPosted())
    }

    @Test
    fun `failed posted action releases execution guard and leaves later actions queued`() {
        val fixture = Fixture()
        val attempted = mutableListOf<Int>()
        fixture.registerProbe { action ->
            attempted.add(action.number)
            check(action.number != 1) { "Processor failure" }
            ActionResult.SUCCESS
        }
        fixture.module.actions.post(ProbeAction(1))
        fixture.module.actions.post(ProbeAction(2))

        assertFailsWith<IllegalStateException> { fixture.module.actions.drainPosted() }
        assertEquals(listOf(1), attempted)
        assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(ProbeAction(3)))
        assertEquals(1, fixture.module.actions.drainPosted())
        assertEquals(listOf(1, 3, 2), attempted)
    }

    @Test
    fun `validator exceptions release execution guard before the next request`() {
        val fixture = Fixture()
        val executed = mutableListOf<Int>()
        fixture.registerProbe { action ->
            executed.add(action.number)
            ActionResult.SUCCESS
        }
        fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<ProbeAction> {
            override val actionClass = ProbeAction::class.java
            override fun validate(action: ProbeAction): ActionValidator.Result {
                check(action.number != 1) { "Validator failure" }
                return ActionValidator.Result.Neutral
            }
        })

        assertFailsWith<IllegalStateException> { fixture.module.actions.execute(ProbeAction(1)) }
        assertTrue(executed.isEmpty())
        assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(ProbeAction(2)))
        assertEquals(listOf(2), executed)
    }

    @Test
    fun `save and reload preserve owner type damage and maturation without another placement`() {
        val fixture = Fixture()
        val startedAt = 1_793_204_123_456L
        val clock = MutableClock(startedAt)
        fixture.register(behavior = MaturingHealthBehavior(20.0, 100.0, 1_000L, clock))
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        assertEquals(ActionResult.SUCCESS, fixture.breakCitadel(STRANGER_CONTEXT, 15.0))
        val oldHandle = fixture.handle()

        fixture.manager.unloadChunk(WORLD, 0, 0).join()
        clock.timeMillis = startedAt + 500L
        fixture.load()

        val restored = fixture.handle()
        assertFalse(oldHandle.isCurrent)
        assertEquals(OWNER, restored.getComponent(Owner::class.java)?.playerId)
        assertEquals(DEFAULT_KIND, restored.getComponent(CitadelKind::class.java)?.definitionKey)
        assertEquals(startedAt, restored.getComponent(Maturing::class.java)?.startedAtMillis)
        assertEquals(5.0, fixture.health().health)
        assertEquals(15.0, fixture.module.health.getCurrentHealth(WORLD, POSITION.x, POSITION.y, POSITION.z))
        assertEquals(60.0, fixture.health().maxHealth)
        assertEquals(listOf(POSITION), fixture.placements)
        assertTrue(fixture.chunk().isDirty)
    }

    @Test
    fun `removal using an expired handle cannot delete a replacement at the same position`() {
        val fixture = Fixture()
        fixture.register()
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        val oldHandle = fixture.handle()
        assertEquals(ActionResult.SUCCESS, fixture.breakCitadel(OWNER_CONTEXT, 1.0))
        assertEquals(ActionResult.SUCCESS, fixture.place(context = STRANGER_CONTEXT))
        fixture.chunk().resetDirty()

        assertEquals(ActionResult.DENIED, fixture.module.remove.remove(oldHandle, RemovalCause.DESTROYED))

        assertEquals(STRANGER, fixture.handle().getComponent(Owner::class.java)?.playerId)
        assertEquals(1, fixture.removals.size)
        assertFalse(fixture.chunk().isDirty)
    }

    private class Fixture(removalPolicy: RemovalPolicy = RemovalPolicy.DENY) {
        val types = TypeManager()
        val mapper = ChunkCordSnapshotMapper(types)
        val backend = MemoryBackend()
        val storage = ChunkBackendLifecycleCoordinator(backend, DirectScheduler, mapper, Executor(Runnable::run))
        val manager = SimpleComponentManager(storage, types)
        val module = CitadelModule(manager, removalPolicy)
        val placements = mutableListOf<CitadelPosition>()
        val removals = mutableListOf<Pair<CitadelPosition, RemovalCause>>()
        val removalSawExistingEntity = mutableListOf<Boolean>()

        init {
            module.lifecycle.addListener(object : CitadelLifecycleListener {
                override fun onPlaced(position: CitadelPosition, runtimeId: Int) {
                    placements.add(position)
                }

                override fun onRemoved(position: CitadelPosition, runtimeId: Int, cause: RemovalCause) {
                    removals.add(position to cause)
                    removalSawExistingEntity.add(find(position) != null)
                }
            })
        }

        fun register(
            key: Key = DEFAULT_KIND,
            behavior: HealthBehavior? = SimpleHealthBehavior(100.0),
            policy: BreakPolicy = OwnerOrDamagePolicy,
            initializers: List<CitadelInitializer> = listOf(OwnerInitializer())
        ) {
            module.registerDefinition(CitadelDefinition(key, initializers), policy, behavior)
        }

        fun load() { manager.loadChunk(WORLD, 0, 0).join() }

        fun place(
            position: CitadelPosition = POSITION,
            key: Key = DEFAULT_KIND,
            context: ActionContext = OWNER_CONTEXT
        ): ActionResult = module.actions.execute(PlaceAction(context, position, key))

        fun breakCitadel(context: ActionContext, damage: Double, position: CitadelPosition = POSITION): ActionResult =
            module.actions.execute(BreakAction(context, position, damage))

        fun find(position: CitadelPosition = POSITION) =
            manager.findCitadel(position.world, position.x, position.y, position.z)

        fun handle(position: CitadelPosition = POSITION) = assertNotNull(find(position))
        fun health(position: CitadelPosition = POSITION) = assertNotNull(handle(position).getComponent(Health::class.java))
        fun chunk() = assertNotNull(storage.getIfChunkLoaded(WXZCord(WORLD, 0, 0)))

        fun <T : ActionData> reject(type: Class<T>) {
            module.actions.validatorCoordinator.registerValidator(object : ActionValidator<T> {
                override val actionClass = type
                override fun validate(action: T) = ActionValidator.Result.Rejected
            })
        }

        fun registerProbe(handler: (ProbeAction) -> ActionResult) {
            module.actions.processorCoordinator.registerProcessor(object : ActionProcessor<ProbeAction>() {
                override val actionClass = ProbeAction::class.java
                override val actionKey = PROBE_KEY
                override fun execute(action: ProbeAction): ActionResult = handler(action)
            })
        }
    }

    private class ProbeAction(val number: Int) : ActionData(PROBE_KEY, WORLD, 0, 0, 0)

    private class MemoryBackend : DBStorage {
        private val persisted = mutableMapOf<WXZCord, ChunkCordSnapshot>()

        override fun loadChunk(location: WXZCord): CompletableFuture<ChunkCordSnapshot?> =
            CompletableFuture.completedFuture(persisted[location])

        override fun saveChunk(snapshot: ChunkCordSnapshot): CompletableFuture<Void> {
            persisted[snapshot.location] = ChunkCordSnapshot.fromByteArray(snapshot.toByteArray())
            return CompletableFuture.completedFuture(null)
        }
    }

    private object DirectScheduler : Scheduler {
        override fun <T> schedule(location: WXZCord, task: Callable<T>): CompletableFuture<T> = try {
            CompletableFuture.completedFuture(task.call())
        } catch (exception: Exception) {
            CompletableFuture.failedFuture(exception)
        }
    }

    private class MutableClock(var timeMillis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = Instant.ofEpochMilli(timeMillis)
        override fun millis(): Long = timeMillis
    }

    private companion object {
        val WORLD: UUID = UUID(1L, 2L)
        val OWNER: UUID = UUID(3L, 4L)
        val STRANGER: UUID = UUID(5L, 6L)
        val ADMIN: UUID = UUID(7L, 8L)
        val POSITION = CitadelPosition(WORLD, 1, 64, 2)
        val SECOND_POSITION = CitadelPosition(WORLD, 3, 64, 4)
        val DEFAULT_KIND = Key("test", "basic")
        val MATURING_KIND = Key("test", "maturing")
        val PLAYER_SOURCE = Key("test", "player")
        val PROBE_KEY = Key("test", "probe")
        val OWNER_CONTEXT = ActionContext(OWNER, PLAYER_SOURCE)
        val STRANGER_CONTEXT = ActionContext(STRANGER, PLAYER_SOURCE)
    }
}
