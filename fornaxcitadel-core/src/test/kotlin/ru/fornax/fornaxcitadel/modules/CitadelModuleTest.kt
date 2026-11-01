package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleListener
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
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
import ru.fornax.fornaxcitadel.core.systems.action.ActionSource
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.NamedActionSource
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.DamagePolicy
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthBehavior
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.health.MaturingHealthBehavior
import ru.fornax.fornaxcitadel.modules.health.SimpleHealthBehavior
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CitadelModuleTest {
    @Test
    fun `placement publishes a complete citadel and occupied placement leaves it unchanged`() {
        val fixture = Fixture()
        val clock = MutableClock(1_793_204_123_456L)
        fixture.register(behavior = MaturingHealthBehavior(20.0, 100.0, 1_000L, clock))
        fixture.load()
        var observedKind: Key? = null
        var observedHealth: Double? = null
        var observedStart: Long? = null
        fixture.module.lifecycle.addListener(object : CitadelLifecycleListener {
            override fun onPlaced(position: CitadelPosition, runtimeId: Int) {
                val handle = fixture.find(position) ?: return
                observedKind = handle.getComponent(CitadelKind::class.java)?.definitionKey
                observedHealth = handle.getComponent(Health::class.java)?.health
                observedStart = handle.getComponent(Maturing::class.java)?.startedAtMillis
            }
        })

        assertEquals(ActionResult.SUCCESS, fixture.place())

        assertEquals(DEFAULT_KIND, observedKind)
        assertEquals(20.0, observedHealth)
        assertEquals(clock.timeMillis, observedStart)
        assertEquals(listOf(POSITION), fixture.placements)
        val original = fixture.handle()
        fixture.chunk().resetDirty()

        assertEquals(ActionResult.DENIED, fixture.place(context = STRANGER_CONTEXT))
        assertTrue(original.isCurrent)
        assertEquals(20.0, fixture.health().health)
        assertEquals(clock.timeMillis, fixture.handle().getComponent(Maturing::class.java)?.startedAtMillis)
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
    fun `custom removal policy dismantles immediately only after global validation allows the action`() {
        val fixture = Fixture(RemovalPolicy { context, _ -> context.actorId == PLACER })
        fixture.register(policy = DamagePolicy.DENY)
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())

        assertEquals(ActionResult.SUCCESS, fixture.remove(PLACER_CONTEXT))

        assertNull(fixture.find())
        assertEquals(listOf(RemovalCause.ADMIN), fixture.removals.map { it.second })
        assertEquals(listOf(false), fixture.removalSawExistingEntity)

        val protected = Fixture(RemovalPolicy { context, _ -> context.actorId == PLACER })
        protected.register(policy = DamagePolicy.DENY)
        protected.load()
        assertEquals(ActionResult.SUCCESS, protected.place())
        protected.chunk().resetDirty()
        protected.reject(RemoveAction::class.java)

        assertEquals(ActionResult.DENIED, protected.remove(PLACER_CONTEXT))
        assertEquals(100.0, protected.health().health)
        assertFalse(protected.chunk().isDirty)
        assertTrue(protected.removals.isEmpty())
    }

    @Test
    fun `damage updates health and death removes once without running removal vetoes`() {
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

        assertEquals(ActionResult.SUCCESS, fixture.damage(STRANGER_CONTEXT, 25.0))
        assertEquals(75.0, fixture.health().health)
        assertTrue(fixture.removals.isEmpty())
        assertEquals(ActionResult.SUCCESS, fixture.damage(STRANGER_CONTEXT, 75.0))

        assertNull(fixture.find())
        assertEquals(0, removalValidationCalls)
        assertEquals(listOf(RemovalCause.DESTROYED), fixture.removals.map { it.second })
        assertEquals(ActionResult.DENIED, fixture.damage(STRANGER_CONTEXT, 1.0))
        assertEquals(1, fixture.removals.size)
    }

    @Test
    fun `invulnerable type rejects damage from players and specialized sources even with health`() {
        val fixture = Fixture()
        fixture.register(policy = DamagePolicy.DENY)
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        fixture.chunk().resetDirty()

        assertEquals(ActionResult.DENIED, fixture.damage(PLACER_CONTEXT, 1_000.0))
        assertEquals(ActionResult.DENIED, fixture.damage(STRANGER_CONTEXT, 1_000.0))
        assertEquals(
            ActionResult.DENIED,
            fixture.module.actions.execute(SpecializedDamage(ActionContext.SYSTEM, POSITION, 1_000.0))
        )

        assertEquals(100.0, fixture.health().health)
        assertFalse(fixture.chunk().isDirty)
        assertTrue(fixture.removals.isEmpty())
    }

    @Test
    fun `invulnerable definition needs no artificial health component`() {
        val fixture = Fixture()
        fixture.register(behavior = null, policy = DamagePolicy.DENY)
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())

        assertNull(fixture.handle().getComponent(Health::class.java))
        assertNull(fixture.module.health.getCurrentHealth(WORLD, POSITION.x, POSITION.y, POSITION.z))
        assertEquals(ActionResult.DENIED, fixture.damage(PLACER_CONTEXT, 100.0))
        assertEquals(
            ActionResult.DENIED,
            fixture.module.actions.execute(SpecializedDamage(ActionContext.SYSTEM, POSITION, 100.0))
        )
        assertNotNull(fixture.find())
        assertTrue(fixture.removals.isEmpty())
    }

    @Test
    fun `custom type policies and common validators apply to every damage action`() {
        val fixture = Fixture()
        fixture.register(policy = DamagePolicy { context, citadel ->
            context.actorId == PLACER && citadel.position == POSITION
        })
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        fixture.chunk().resetDirty()

        assertEquals(ActionResult.DENIED, fixture.damage(STRANGER_CONTEXT, 10.0))
        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
            SpecializedDamage(ActionContext.SYSTEM, POSITION, 10.0)
        ))
        assertEquals(100.0, fixture.health().health)
        assertFalse(fixture.chunk().isDirty)

        assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(
            SpecializedDamage(PLACER_CONTEXT, POSITION, 10.0)
        ))
        assertEquals(90.0, fixture.health().health)
        fixture.chunk().resetDirty()
        fixture.reject(DamageAction::class.java)

        assertEquals(ActionResult.DENIED, fixture.damage(PLACER_CONTEXT, 10.0))
        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
            SpecializedDamage(PLACER_CONTEXT, POSITION, 10.0)
        ))
        assertEquals(90.0, fixture.health().health)
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `posted damage retains its actor and typed source for policies and validators`() {
        val fixture = Fixture()
        val source = ProjectileSource(UUID(9L, 10L), 4.0)
        val context = ActionContext(PLACER, source)
        val policyContexts = mutableListOf<ActionContext>()
        fixture.register(policy = DamagePolicy { candidate, citadel ->
            policyContexts.add(candidate)
            val projectile = candidate.source as? ProjectileSource
            candidate.actorId == PLACER && projectile != null && projectile.projectileId == source.projectileId &&
                projectile.power == 4.0 && citadel.position == POSITION
        })
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        val validatedContexts = mutableListOf<ActionContext>()
        fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun validate(action: DamageAction): ActionValidator.Result {
                validatedContexts.add(action.context)
                return ActionValidator.Result.Neutral
            }
        })
        fixture.module.actions.post(DamageAction(context, POSITION, 10.0))
        fixture.module.actions.post(DamageAction(ActionContext(PLACER, NamedActionSource(source.key)), POSITION, 10.0))

        assertEquals(100.0, fixture.health().health)
        assertTrue(policyContexts.isEmpty())
        assertEquals(1, fixture.module.actions.drainPosted(1))
        assertEquals(90.0, fixture.health().health)
        assertSame(context, policyContexts.single())
        assertSame(source, validatedContexts.single().source)
        assertEquals(PLACER, validatedContexts.single().actorId)
        fixture.chunk().resetDirty()

        // Reusing a key cannot supply the typed information required by the policy.
        assertEquals(1, fixture.module.actions.drainPosted())
        assertEquals(90.0, fixture.health().health)
        assertFalse(fixture.chunk().isDirty)
        assertEquals(2, policyContexts.size)
        assertEquals(1, validatedContexts.size)
    }

    @Test
    fun `a type with health but no registered damage policy rejects damage`() {
        val fixture = Fixture()
        val behavior = SimpleHealthBehavior(100.0)
        fixture.module.definitions.register(CitadelDefinition(DEFAULT_KIND, listOf(HealthInitializer(behavior))))
        fixture.module.health.registerBehavior(DEFAULT_KIND, behavior)
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        fixture.chunk().resetDirty()

        assertEquals(ActionResult.DENIED, fixture.damage(PLACER_CONTEXT, 100.0))
        assertEquals(100.0, fixture.health().health)
        assertFalse(fixture.chunk().isDirty)
        assertTrue(fixture.removals.isEmpty())
    }

    @Test
    fun `unloaded absent unknown occupied and unhandled actions are denied`() {
        val fixture = Fixture()
        fixture.register()

        assertEquals(ActionResult.DENIED, fixture.place())
        assertEquals(ActionResult.DENIED, fixture.damage(STRANGER_CONTEXT, 1.0))
        fixture.load()
        assertEquals(ActionResult.DENIED, fixture.damage(STRANGER_CONTEXT, 1.0))
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
        assertEquals(ActionResult.SUCCESS, fixture.damage(STRANGER_CONTEXT, 5.0))
        assertEquals(ActionResult.SUCCESS, fixture.damage(STRANGER_CONTEXT, 10.0, SECOND_POSITION))
        assertEquals(20.0, fixture.health().health)
        assertEquals(50.0, fixture.health(SECOND_POSITION).health)
    }

    @Test
    fun `administrative removal requires explicit authorization and source names grant no privilege`() {
        val fixture = Fixture(RemovalPolicy { context, _ -> context.actorId == ADMIN })
        fixture.register()
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        val forgedSource = ActionContext(STRANGER, NamedActionSource(Key("test", "admin")))

        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(RemoveAction(forgedSource, POSITION)))
        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(RemoveAction(PLACER_CONTEXT, POSITION)))
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
        val fixture = Fixture(RemovalPolicy { context, _ -> context.actorId == PLACER })
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
        assertEquals(ActionResult.SUCCESS, fixture.remove(PLACER_CONTEXT))

        assertNull(fixture.find())
        assertEquals(1, laterPlaced)
        assertEquals(1, laterRemoved)
    }

    @Test
    fun `observers can post removal but cannot mutate or recursively execute before other observers finish`() {
        val fixture = Fixture(RemovalPolicy { context, _ -> context.actorId == PLACER })
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
                    fixture.module.actions.execute(RemoveAction(PLACER_CONTEXT, position))
                } catch (failure: IllegalStateException) {
                    nestedExecutionFailure = failure
                }
                try {
                    fixture.module.actions.drainPosted()
                } catch (failure: IllegalStateException) {
                    nestedDrainFailure = failure
                }
                fixture.module.actions.post(RemoveAction(PLACER_CONTEXT, position))
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
        assertEquals(listOf(RemovalCause.ADMIN), fixture.removals.map { it.second })
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
    fun `save and reload preserve type damage and maturation without another placement`() {
        val fixture = Fixture()
        val startedAt = 1_793_204_123_456L
        val clock = MutableClock(startedAt)
        fixture.register(behavior = MaturingHealthBehavior(20.0, 100.0, 1_000L, clock))
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        assertEquals(ActionResult.SUCCESS, fixture.damage(STRANGER_CONTEXT, 15.0))
        val oldHandle = fixture.handle()

        fixture.manager.unloadChunk(WORLD, 0, 0).join()
        clock.timeMillis = startedAt + 500L
        fixture.load()

        val restored = fixture.handle()
        assertFalse(oldHandle.isCurrent)
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
        assertEquals(ActionResult.SUCCESS, fixture.damage(PLACER_CONTEXT, 100.0))
        assertEquals(ActionResult.SUCCESS, fixture.place(context = STRANGER_CONTEXT))
        val replacement = fixture.handle()
        fixture.chunk().resetDirty()

        assertEquals(ActionResult.DENIED, fixture.module.remove.remove(oldHandle, RemovalCause.DESTROYED))

        assertFalse(oldHandle.isCurrent)
        assertTrue(replacement.isCurrent)
        assertEquals(100.0, fixture.health().health)
        assertEquals(1, fixture.removals.size)
        assertFalse(fixture.chunk().isDirty)
    }

    private class Fixture(removalPolicy: RemovalPolicy = RemovalPolicy.DENY) {
        val types = TypeManager()
        val mapper = ChunkCordSnapshotMapper(types)
        val backend = MemoryBackend()
        val storage = ChunkBackendLifecycleCoordinator(backend, DirectScheduler, mapper, Executor(Runnable::run))
        val manager = ComponentManager(storage, types)
        val module = CitadelModule(manager, removalPolicy).apply { enable() }
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
            policy: DamagePolicy = DamagePolicy.ALLOW,
            initializers: List<CitadelInitializer> = emptyList()
        ) {
            module.registerDefinition(CitadelDefinition(key, initializers), policy, behavior)
        }

        fun load() { manager.loadChunk(WORLD, 0, 0).join() }

        fun place(
            position: CitadelPosition = POSITION,
            key: Key = DEFAULT_KIND,
            context: ActionContext = PLACER_CONTEXT
        ): ActionResult = module.actions.execute(PlaceAction(context, position, key))

        fun damage(context: ActionContext, damage: Double, position: CitadelPosition = POSITION): ActionResult =
            module.actions.execute(DamageAction(context, position, damage))

        fun remove(context: ActionContext, position: CitadelPosition = POSITION): ActionResult =
            module.actions.execute(RemoveAction(context, position))

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

    private class SpecializedDamage(context: ActionContext, position: CitadelPosition, damage: Double) :
        DamageAction(context, position, damage)

    private data class ProjectileSource(val projectileId: UUID, val power: Double) : ActionSource {
        override val key = Key("test", "projectile")
    }

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
        val PLACER: UUID = UUID(3L, 4L)
        val STRANGER: UUID = UUID(5L, 6L)
        val ADMIN: UUID = UUID(7L, 8L)
        val POSITION = CitadelPosition(WORLD, 1, 64, 2)
        val SECOND_POSITION = CitadelPosition(WORLD, 3, 64, 4)
        val DEFAULT_KIND = Key("test", "basic")
        val MATURING_KIND = Key("test", "maturing")
        val PLAYER_SOURCE = NamedActionSource(Key("test", "player"))
        val PROBE_KEY = Key("test", "probe")
        val PLACER_CONTEXT = ActionContext(PLACER, PLAYER_SOURCE)
        val STRANGER_CONTEXT = ActionContext(STRANGER, PLAYER_SOURCE)
    }
}
