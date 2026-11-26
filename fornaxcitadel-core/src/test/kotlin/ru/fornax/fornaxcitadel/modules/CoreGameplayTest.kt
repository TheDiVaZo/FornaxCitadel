package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.addon.CoreGameplay
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.resolver.ExactPositionResolver
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.facade.ReadableHandle
import ru.fornax.fornaxcitadel.core.scheduler.ManualScheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.DBStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionSource
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.NamedActionSource
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.DamageModifier
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthDefinition
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.health.MaturingInitializer
import ru.fornax.fornaxcitadel.modules.health.MaturationDefinition
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
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

class CoreGameplayTest {
    @Test
    fun `addon rejects duplicate registrations while its registry accepts the same instance again`() {
        val addon = Fixture().addon
        val original = CitadelDefinition(DEFAULT_KIND, emptyList())
        val replacement = CitadelDefinition(DEFAULT_KIND, emptyList())
        addon.registerDefinition(original)

        assertFailsWith<IllegalArgumentException> { addon.registerDefinition(original) }
        assertFailsWith<IllegalArgumentException> { addon.registerDefinition(replacement) }
        addon.definitions.register(original)
        assertFailsWith<IllegalArgumentException> { addon.definitions.register(replacement) }
        assertSame(original, addon.definitions.find(DEFAULT_KIND))
    }

    @Test
    fun `placement publishes a complete citadel and occupied placement leaves it unchanged`() {
        val fixture = Fixture()
        val clock = Clock.fixed(Instant.ofEpochMilli(1_793_204_123_456L), ZoneOffset.UTC)
        fixture.register(initializers = listOf(MaturingInitializer(clock)))
        fixture.addon.health.maturation.registerDefinition(DEFAULT_KIND, MaturationDefinition(Duration.ofSeconds(1), 5.0))
        fixture.load()
        var observedKind: Key? = null
        var observedHealth: Double? = null
        var observedStart: Long? = null
        fixture.actions.registerHandler(object : ActionHandler<PlaceAction> {
            override val actionClass = PlaceAction::class.java
            override fun handle(action: PlaceAction) {
                val handle = fixture.find(action.position) ?: return
                observedKind = handle.getComponent(CitadelKind::class.java)?.definitionKey
                observedHealth = handle.getComponent(Health::class.java)?.health
                observedStart = handle.getComponent(Maturing::class.java)?.startedAtMillis
            }
        })

        assertEquals(ActionResult.SUCCESS, fixture.place())

        assertEquals(DEFAULT_KIND, observedKind)
        assertEquals(100.0, observedHealth)
        assertEquals(clock.millis(), observedStart)
        assertEquals(listOf(POSITION), fixture.placements)
        val original = fixture.handle()
        fixture.chunk().resetDirty()

        assertEquals(ActionResult.DENIED, fixture.place(context = STRANGER_CONTEXT))
        assertTrue(original.isCurrent)
        assertEquals(100.0, fixture.health().health)
        assertEquals(clock.millis(), fixture.handle().getComponent(Maturing::class.java)?.startedAtMillis)
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
        fixture.register(initializers = listOf(placementInitializer { draft ->
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
    fun `custom removal validator dismantles immediately only after global validation allows the action`() {
        val fixture = Fixture({ context, _ -> context.actorId == PLACER })
        fixture.register(authorizeDamage = { _, _ -> false })
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())

        assertEquals(ActionResult.SUCCESS, fixture.remove(PLACER_CONTEXT))

        assertNull(fixture.find())
        assertEquals(listOf(POSITION), fixture.removals.map { it.position })
        assertEquals(listOf(false), fixture.removalSawExistingEntity)

        val protected = Fixture({ context, _ -> context.actorId == PLACER })
        protected.register(authorizeDamage = { _, _ -> false })
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
        fixture.actions.registerValidator(object : ActionValidator<RemoveAction> {
            override val actionClass = RemoveAction::class.java
            override fun validate(action: RemoveAction): ActionValidator.Result {
                removalValidationCalls++
                return ActionValidator.Result.Rejected
            }
        })

        val hit = DamageAction(STRANGER_CONTEXT, POSITION, 25.0)
        val lethalHit = DamageAction(STRANGER_CONTEXT, POSITION, 75.0)
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(hit))
        assertEquals(75.0, fixture.health().health)
        assertTrue(fixture.removals.isEmpty())
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(lethalHit))

        assertNull(fixture.find())
        assertEquals(0, removalValidationCalls)
        assertEquals(listOf(hit, lethalHit), fixture.damages)
        assertSame(lethalHit, fixture.damages.last())
        assertEquals(listOf(true, false), fixture.damageSawExistingEntity)
        assertTrue(fixture.removals.isEmpty())
        assertEquals(ActionResult.DENIED, fixture.damage(STRANGER_CONTEXT, 1.0))
        assertEquals(2, fixture.damages.size)
    }

    @Test
    fun `invulnerable type rejects damage from players and specialized sources even with health`() {
        val fixture = Fixture()
        fixture.register(authorizeDamage = { _, _ -> false })
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        fixture.chunk().resetDirty()

        assertEquals(ActionResult.DENIED, fixture.damage(PLACER_CONTEXT, 1_000.0))
        assertEquals(ActionResult.DENIED, fixture.damage(STRANGER_CONTEXT, 1_000.0))
        assertEquals(
            ActionResult.DENIED,
            fixture.actions.execute(SpecializedDamage(ActionContext.SYSTEM, POSITION, 1_000.0))
        )

        assertEquals(100.0, fixture.health().health)
        assertFalse(fixture.chunk().isDirty)
        assertTrue(fixture.removals.isEmpty())
    }

    @Test
    fun `invulnerable definition needs no artificial health component`() {
        val fixture = Fixture()
        fixture.register(maxHealth = null, authorizeDamage = { _, _ -> false })
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())

        assertNull(fixture.handle().getComponent(Health::class.java))
        assertEquals(ActionResult.DENIED, fixture.damage(PLACER_CONTEXT, 100.0))
        assertEquals(
            ActionResult.DENIED,
            fixture.actions.execute(SpecializedDamage(ActionContext.SYSTEM, POSITION, 100.0))
        )
        assertNotNull(fixture.find())
        assertTrue(fixture.removals.isEmpty())
    }

    @Test
    fun `explicit destruction is denied by default even when ordinary damage is allowed`() {
        for (withHealth in listOf(false, true)) {
            val fixture = Fixture()
            fixture.register(maxHealth = if (withHealth) 100.0 else null)
            fixture.load()
            assertEquals(ActionResult.SUCCESS, fixture.place())
            val original = fixture.handle()
            fixture.chunk().resetDirty()

            for (context in listOf(PLACER_CONTEXT, ActionContext.SYSTEM)) {
                assertEquals(ActionResult.DENIED, fixture.destroy(context))
            }

            assertTrue(original.isCurrent)
            if (withHealth) assertEquals(100.0, fixture.health().health)
            assertFalse(fixture.chunk().isDirty)
            assertTrue(fixture.removals.isEmpty())
        }
    }

    @Test
    fun `authorized destruction removes protected healthy and healthless citadels without damage modifiers`() {
        for (withHealth in listOf(false, true)) {
            var modifierCalls = 0
            val fixture = Fixture(authorizeDestruction = { context, citadel ->
                context.actorId == PLACER && citadel.position == POSITION
            })
            fixture.addon.health.registerModifier(DamageModifier { _, _, _ ->
                modifierCalls++
                error("Destruction must not calculate damage")
            })
            fixture.register(maxHealth = if (withHealth) 100.0 else null, authorizeDamage = { _, _ -> false })
            fixture.load()
            assertEquals(ActionResult.SUCCESS, fixture.place())
            if (withHealth) assertTrue(fixture.handle().editComponent(Health::class.java) { it.health = 73.0 })
            fixture.manager.saveAll().join()
            val original = fixture.handle()
            val originalHealth = original.getComponent(Health::class.java)
            fixture.chunk().resetDirty()
            // Explicit destruction stays in the damage pipeline; administrative validators do not apply.
            fixture.reject(RemoveAction::class.java)

            assertEquals(ActionResult.DENIED, fixture.destroy(STRANGER_CONTEXT))
            assertFalse(fixture.chunk().isDirty)
            assertEquals(ActionResult.SUCCESS, fixture.destroy(PLACER_CONTEXT))

            assertEquals(0, modifierCalls)
            if (withHealth) assertEquals(73.0, assertNotNull(originalHealth).health)
            assertFalse(original.isCurrent)
            assertNull(fixture.find())
            assertTrue(fixture.chunk().isEmpty)
            assertTrue(fixture.chunk().isDirty)
            assertEquals(POSITION, fixture.damages.single().position)
            assertEquals(DamageAction.Mode.DESTROY, fixture.damages.single().mode)
            assertTrue(fixture.removals.isEmpty())
            assertEquals(listOf(false), fixture.damageSawExistingEntity)
            assertEquals(ActionResult.DENIED, fixture.destroy(PLACER_CONTEXT))
            assertEquals(1, fixture.damages.size)

            fixture.manager.unloadChunk(WORLD, 0, 0).join()
            fixture.load()

            assertNull(fixture.find())
            assertTrue(fixture.chunk().isEmpty)
            assertEquals(1, fixture.damages.size)
        }
    }

    @Test
    fun `generic and damage validators can reject authorized destruction`() {
        for (rejectedType in listOf(Action::class.java, DamageAction::class.java)) {
            val fixture = Fixture(authorizeDestruction = { _, _ -> true })
            fixture.register(authorizeDamage = { _, _ -> false })
            fixture.load()
            assertEquals(ActionResult.SUCCESS, fixture.place())
            fixture.chunk().resetDirty()
            fixture.reject(rejectedType)

            assertEquals(ActionResult.DENIED, fixture.destroy(PLACER_CONTEXT))

            assertEquals(100.0, fixture.health().health)
            assertFalse(fixture.chunk().isDirty)
            assertTrue(fixture.removals.isEmpty())
        }
    }

    @Test
    fun `queued destruction uses authorization at execution and validates once`() {
        var authorized = true
        var validationCalls = 0
        val fixture = Fixture(authorizeDestruction = { _, _ ->
            validationCalls++
            authorized
        })
        fixture.register()
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        fixture.chunk().resetDirty()
        fixture.actions.post(DamageAction(PLACER_CONTEXT, POSITION, 0.0, DamageAction.Mode.DESTROY))
        assertEquals(0, validationCalls)
        authorized = false

        assertEquals(1, fixture.actions.drainPosted())

        assertEquals(1, validationCalls)
        assertEquals(100.0, fixture.health().health)
        assertFalse(fixture.chunk().isDirty)
        assertTrue(fixture.removals.isEmpty())
        authorized = true
        assertEquals(ActionResult.SUCCESS, fixture.destroy(PLACER_CONTEXT))
        assertEquals(2, validationCalls)
        assertEquals(POSITION, fixture.damages.single().position)
        assertEquals(DamageAction.Mode.DESTROY, fixture.damages.single().mode)
        assertTrue(fixture.removals.isEmpty())
    }

    @Test
    fun `destruction denies missing and unloaded targets without loading chunks`() {
        var validationCalls = 0
        val fixture = Fixture(authorizeDestruction = { _, _ -> validationCalls++; true })
        fixture.register()

        assertEquals(ActionResult.DENIED, fixture.destroy(PLACER_CONTEXT))
        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))
        fixture.load()
        assertEquals(ActionResult.DENIED, fixture.destroy(PLACER_CONTEXT))
        assertFalse(fixture.chunk().isDirty)
        assertEquals(ActionResult.SUCCESS, fixture.place())
        fixture.manager.unloadChunk(WORLD, 0, 0).join()

        assertEquals(ActionResult.DENIED, fixture.destroy(PLACER_CONTEXT))

        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))
        assertEquals(0, validationCalls)
        assertTrue(fixture.removals.isEmpty())
        fixture.load()
        assertEquals(100.0, fixture.health().health)
    }

    @Test
    fun `legacy damage constructors retain ordinary damage and never use destruction authorization`() {
        val fixture = Fixture(authorizeDestruction = { _, _ ->
            error("Ordinary damage must not use destruction authorization")
        })
        fixture.register()
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        val actions = listOf(
            DamageAction(PLACER_CONTEXT, POSITION, 10.0),
            DamageAction(WORLD, POSITION.x, POSITION.y, POSITION.z, 10.0)
        )

        for (action in actions) {
            assertEquals(DamageAction.Mode.DAMAGE, action.mode)
            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(action))
        }

        assertEquals(80.0, fixture.health().health)
        assertTrue(fixture.removals.isEmpty())
    }

    @Test
    fun `type validators and common validators apply to every damage action`() {
        val fixture = Fixture()
        fixture.register(authorizeDamage = { context, citadel ->
            context.actorId == PLACER && citadel.position == POSITION
        })
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        fixture.chunk().resetDirty()

        assertEquals(ActionResult.DENIED, fixture.damage(STRANGER_CONTEXT, 10.0))
        assertEquals(ActionResult.DENIED, fixture.actions.execute(
            SpecializedDamage(ActionContext.SYSTEM, POSITION, 10.0)
        ))
        assertEquals(100.0, fixture.health().health)
        assertFalse(fixture.chunk().isDirty)

        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(
            SpecializedDamage(PLACER_CONTEXT, POSITION, 10.0)
        ))
        assertEquals(90.0, fixture.health().health)
        fixture.chunk().resetDirty()
        fixture.reject(DamageAction::class.java)

        assertEquals(ActionResult.DENIED, fixture.damage(PLACER_CONTEXT, 10.0))
        assertEquals(ActionResult.DENIED, fixture.actions.execute(
            SpecializedDamage(PLACER_CONTEXT, POSITION, 10.0)
        ))
        assertEquals(90.0, fixture.health().health)
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `posted damage retains its actor and typed source for authorization and other validators`() {
        val fixture = Fixture()
        val source = ProjectileSource(UUID(9L, 10L), 4.0)
        val context = ActionContext(PLACER, source)
        val authorizationContexts = mutableListOf<ActionContext>()
        fixture.register(authorizeDamage = { candidate, citadel ->
            authorizationContexts.add(candidate)
            val projectile = candidate.source as? ProjectileSource
            candidate.actorId == PLACER && projectile != null && projectile.projectileId == source.projectileId &&
                projectile.power == 4.0 && citadel.position == POSITION
        })
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        val validatedContexts = mutableListOf<ActionContext>()
        fixture.actions.registerValidator(object : ActionValidator<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun validate(action: DamageAction): ActionValidator.Result {
                validatedContexts.add(action.context)
                return ActionValidator.Result.Neutral
            }
        })
        fixture.actions.post(DamageAction(context, POSITION, 10.0))
        fixture.actions.post(DamageAction(ActionContext(PLACER, NamedActionSource(source.key)), POSITION, 10.0))

        assertEquals(100.0, fixture.health().health)
        assertTrue(authorizationContexts.isEmpty())
        assertEquals(1, fixture.actions.drainPosted(1))
        assertEquals(90.0, fixture.health().health)
        // Authorization sees the original context once during validation.
        assertEquals(1, authorizationContexts.size)
        authorizationContexts.forEach { assertSame(context, it) }
        assertSame(source, validatedContexts.single().source)
        assertEquals(PLACER, validatedContexts.single().actorId)
        fixture.chunk().resetDirty()

        // Reusing a key cannot supply the typed information required by the validator.
        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(90.0, fixture.health().health)
        assertFalse(fixture.chunk().isDirty)
        assertEquals(2, authorizationContexts.size)
        assertEquals(1, validatedContexts.size)
    }

    @Test
    fun `a type with health but no approving damage validator rejects damage`() {
        val fixture = Fixture()
        fixture.addon.health.registerDefinition(DEFAULT_KIND, HealthDefinition(100.0))
        fixture.addon.registerDefinition(CitadelDefinition(DEFAULT_KIND, listOf(HealthInitializer(fixture.addon.health))))
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
            fixture.actions.execute(Action(Key("test", "unhandled"), WORLD, 1, 64, 2))
        )
    }

    @Test
    fun `one addon applies maturation per target while stored health stays directly readable`() {
        val fixture = Fixture()
        val clock = Clock.fixed(Instant.ofEpochMilli(Long.MAX_VALUE), ZoneOffset.UTC)
        fixture.register(maxHealth = 25.0)
        fixture.register(MATURING_KIND, initializers = listOf(MaturingInitializer(clock)))
        fixture.addon.health.maturation.registerDefinition(MATURING_KIND, MaturationDefinition(Duration.ofSeconds(1), 5.0))
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        assertEquals(ActionResult.SUCCESS, fixture.place(SECOND_POSITION, MATURING_KIND))
        fixture.chunk().resetDirty()


        assertEquals(100.0, fixture.health(SECOND_POSITION).health)
        assertFalse(fixture.chunk().isDirty)
        assertEquals(ActionResult.SUCCESS, fixture.damage(STRANGER_CONTEXT, 5.0))
        assertEquals(ActionResult.SUCCESS, fixture.damage(STRANGER_CONTEXT, 10.0, SECOND_POSITION))
        assertEquals(20.0, fixture.health().health)
        assertEquals(50.0, fixture.health(SECOND_POSITION).health)

        assertTrue(fixture.handle(SECOND_POSITION).editComponent(Maturing::class.java) { it.startedAtMillis = 0L })
        fixture.chunk().resetDirty()
        assertEquals(50.0, fixture.health(SECOND_POSITION).health)

        assertFalse(fixture.chunk().isDirty)
        assertEquals(ActionResult.SUCCESS, fixture.damage(STRANGER_CONTEXT, 10.0, SECOND_POSITION))
        assertEquals(40.0, fixture.health(SECOND_POSITION).health)
    }

    @Test
    fun `administrative removal requires explicit authorization and source names grant no privilege`() {
        val fixture = Fixture({ context, _ -> context.actorId == ADMIN })
        fixture.register()
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        val forgedSource = ActionContext(STRANGER, NamedActionSource(Key("test", "admin")))

        assertEquals(ActionResult.DENIED, fixture.actions.execute(RemoveAction(forgedSource, POSITION)))
        assertEquals(ActionResult.DENIED, fixture.actions.execute(RemoveAction(PLACER_CONTEXT, POSITION)))
        assertNotNull(fixture.find())
        assertEquals(
            ActionResult.SUCCESS,
            fixture.actions.execute(RemoveAction(ActionContext(ADMIN, PLAYER_SOURCE), POSITION))
        )

        assertNull(fixture.find())
        assertEquals(listOf(POSITION), fixture.removals.map { it.position })
    }

    @Test
    fun `administrative removal is denied by default`() {
        val fixture = Fixture()
        fixture.register()
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())

        assertEquals(ActionResult.DENIED, fixture.actions.execute(RemoveAction(ActionContext.SYSTEM, POSITION)))
        assertNotNull(fixture.find())
    }

    @Test
    fun `direct services change state without dispatching action handlers`() {
        val fixture = Fixture()
        fixture.register()
        fixture.load()

        assertEquals(ActionResult.SUCCESS, fixture.addon.place.place(PlaceAction(PLACER_CONTEXT, POSITION, DEFAULT_KIND)))
        assertEquals(ActionResult.SUCCESS, fixture.addon.place.place(PlaceAction(PLACER_CONTEXT, SECOND_POSITION, DEFAULT_KIND)))
        val first = fixture.handle()
        val second = fixture.handle(SECOND_POSITION)
        assertEquals(ActionResult.SUCCESS, fixture.addon.health.damage(first, 25.0))
        assertEquals(75.0, fixture.health().health)
        assertEquals(ActionResult.SUCCESS, fixture.addon.remove.remove(first))
        assertEquals(1, fixture.addon.remove.removeAll(listOf(first, second)))

        assertFalse(first.isCurrent)
        assertFalse(second.isCurrent)
        assertTrue(fixture.chunk().isEmpty)
        assertTrue(fixture.completed.isEmpty())
    }

    @Test
    fun `handler failures do not undo committed actions or skip subsequent handlers`() {
        val fixture = Fixture({ context, _ -> context.actorId == PLACER })
        fixture.register()
        fixture.load()
        var laterPlaced = 0
        var laterRemoved = 0
        fixture.actions.registerHandler(object : ActionHandler<Action> {
            override val actionClass = Action::class.java
            override fun handle(action: Action) = error("Broken action handler")
        })
        fixture.actions.registerHandler(object : ActionHandler<Action> {
            override val actionClass = Action::class.java
            override fun handle(action: Action) {
                when (action) {
                    is PlaceAction -> laterPlaced++
                    is RemoveAction -> laterRemoved++
                }
            }
        })

        assertEquals(ActionResult.SUCCESS, fixture.place())
        assertNotNull(fixture.find())
        assertEquals(ActionResult.SUCCESS, fixture.remove(PLACER_CONTEXT))

        assertNull(fixture.find())
        assertEquals(1, laterPlaced)
        assertEquals(1, laterRemoved)
    }

    @Test
    fun `handlers can post removal but cannot execute or drain before other handlers finish`() {
        val fixture = Fixture({ context, _ -> context.actorId == PLACER })
        fixture.register()
        fixture.load()
        var nestedExecutionFailure: IllegalStateException? = null
        var nestedDrainFailure: IllegalStateException? = null
        var laterObserverSawEntity = false
        fixture.actions.registerHandler(object : ActionHandler<PlaceAction> {
            override val actionClass = PlaceAction::class.java
            override fun handle(action: PlaceAction) {
                val position = action.position
                try {
                    fixture.actions.execute(RemoveAction(PLACER_CONTEXT, position))
                } catch (failure: IllegalStateException) {
                    nestedExecutionFailure = failure
                }
                try {
                    fixture.actions.drainPosted()
                } catch (failure: IllegalStateException) {
                    nestedDrainFailure = failure
                }
                fixture.actions.post(RemoveAction(PLACER_CONTEXT, position))
            }
        })
        fixture.actions.registerHandler(object : ActionHandler<PlaceAction> {
            override val actionClass = PlaceAction::class.java
            override fun handle(action: PlaceAction) {
                laterObserverSawEntity = fixture.find(action.position) != null
            }
        })

        assertEquals(ActionResult.SUCCESS, fixture.place())

        assertNotNull(nestedExecutionFailure)
        assertNotNull(nestedDrainFailure)
        assertTrue(laterObserverSawEntity)
        assertNotNull(fixture.find())
        assertTrue(fixture.removals.isEmpty())
        assertEquals(1, fixture.actions.drainPosted())
        assertNull(fixture.find())
        assertEquals(listOf(POSITION), fixture.removals.map { it.position })
        assertEquals(0, fixture.actions.drainPosted())
    }

    @Test
    fun `posted actions preserve fifo order and draining respects its limit`() {
        val fixture = Fixture()
        val executed = mutableListOf<Int>()
        fixture.registerProbe { action ->
            executed.add(action.number)
            if (action.number == 1) fixture.actions.post(ProbeAction(3))
            ActionResult.SUCCESS
        }
        fixture.actions.post(ProbeAction(1))
        fixture.actions.post(ProbeAction(2))

        assertEquals(0, fixture.actions.drainPosted(0))
        assertTrue(executed.isEmpty())
        assertFailsWith<IllegalArgumentException> { fixture.actions.drainPosted(-1) }
        assertEquals(1, fixture.actions.drainPosted(1))
        assertEquals(listOf(1), executed)

        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(ProbeAction(4)))
        assertEquals(listOf(1, 4), executed)
        assertEquals(2, fixture.actions.drainPosted())
        assertEquals(listOf(1, 4, 2, 3), executed)
        assertEquals(0, fixture.actions.drainPosted())
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
        fixture.actions.post(ProbeAction(1))
        fixture.actions.post(ProbeAction(2))

        assertFailsWith<IllegalStateException> { fixture.actions.drainPosted() }
        assertEquals(listOf(1), attempted)
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(ProbeAction(3)))
        assertEquals(1, fixture.actions.drainPosted())
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
        fixture.actions.registerValidator(object : ActionValidator<ProbeAction> {
            override val actionClass = ProbeAction::class.java
            override fun validate(action: ProbeAction): ActionValidator.Result {
                check(action.number != 1) { "Validator failure" }
                return ActionValidator.Result.Neutral
            }
        })

        assertFailsWith<IllegalStateException> { fixture.actions.execute(ProbeAction(1)) }
        assertTrue(executed.isEmpty())
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(ProbeAction(2)))
        assertEquals(listOf(2), executed)
    }

    @Test
    fun `save and reload preserve type damage and maturation without another placement`() {
        val fixture = Fixture()
        val startedAt = Long.MAX_VALUE
        val clock = Clock.fixed(Instant.ofEpochMilli(startedAt), ZoneOffset.UTC)
        fixture.register(initializers = listOf(MaturingInitializer(clock)))
        fixture.addon.health.maturation.registerDefinition(DEFAULT_KIND, MaturationDefinition(Duration.ofSeconds(1), 5.0))
        fixture.load()
        assertEquals(ActionResult.SUCCESS, fixture.place())
        assertEquals(ActionResult.SUCCESS, fixture.damage(STRANGER_CONTEXT, 15.0))
        val oldHandle = fixture.handle()

        fixture.manager.unloadChunk(WORLD, 0, 0).join()
        fixture.load()

        val restored = fixture.handle()
        assertFalse(oldHandle.isCurrent)
        assertEquals(DEFAULT_KIND, restored.getComponent(CitadelKind::class.java)?.definitionKey)
        val maturing = assertNotNull(restored.getComponent(Maturing::class.java))
        assertEquals(startedAt, maturing.startedAtMillis)
        assertEquals(25.0, fixture.health().health)

        assertFalse(fixture.chunk().isDirty)
        assertEquals(ActionResult.SUCCESS, fixture.damage(STRANGER_CONTEXT, 1.0))
        assertEquals(20.0, fixture.health().health)

        assertTrue(restored.editComponent(Maturing::class.java) { it.startedAtMillis = 0L })
        fixture.manager.unloadChunk(WORLD, 0, 0).join()
        fixture.load()

        assertEquals(20.0, fixture.health().health)

        assertFalse(fixture.chunk().isDirty)
        assertEquals(ActionResult.SUCCESS, fixture.damage(STRANGER_CONTEXT, 5.0))
        assertEquals(15.0, fixture.health().health)
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

        assertEquals(ActionResult.DENIED, fixture.addon.remove.remove(oldHandle))

        assertFalse(oldHandle.isCurrent)
        assertTrue(replacement.isCurrent)
        assertEquals(100.0, fixture.health().health)
        assertEquals(1, fixture.damages.size)
        assertTrue(fixture.removals.isEmpty())
        assertFalse(fixture.chunk().isDirty)
    }

    private class Fixture(
        authorizeRemoval: ((ActionContext, ReadableHandle) -> Boolean)? = null,
        authorizeDestruction: ((ActionContext, ReadableHandle) -> Boolean)? = null
    ) {
        val types = TypeManager()
        val mapper = ChunkCordSnapshotMapper(types)
        val backend = MemoryBackend()
        val scheduler = ManualScheduler()
        val storage = ChunkBackendLifecycleCoordinator(backend, scheduler, mapper, Executor(Runnable::run))
        val manager = ComponentManager(storage, types)
        val actions = ActionManager()
        val citadels = CitadelFacade(manager, ExactPositionResolver)
        val addon = CoreGameplay(citadels, ActionFacade(actions))

        val completed = mutableListOf<Action>()
        val placements get() = completed.filterIsInstance<PlaceAction>().map { it.position }
        val removals get() = completed.filterIsInstance<RemoveAction>()
        val damages get() = completed.filterIsInstance<DamageAction>()
        val removalSawExistingEntity = mutableListOf<Boolean>()
        val damageSawExistingEntity = mutableListOf<Boolean>()

        init {
            authorizeRemoval?.let { actions.validateRemoval(citadels, it) }
            authorizeDestruction?.let { actions.validateDamage(citadels, mode = DamageAction.Mode.DESTROY, allowed = it) }
            actions.registerHandler(object : ActionHandler<Action> {
                override val actionClass = Action::class.java
                override fun handle(action: Action) {
                    completed.add(action)
                    when (action) {
                        is RemoveAction -> removalSawExistingEntity.add(find(action.position) != null)
                        is DamageAction -> damageSawExistingEntity.add(find(action.position) != null)
                    }
                }
            })
        }

        fun register(
            key: Key = DEFAULT_KIND,
            maxHealth: Double? = 100.0,
            authorizeDamage: (ActionContext, ReadableHandle) -> Boolean = { _, _ -> true },
            initializers: List<CitadelInitializer> = emptyList()
        ) {
            maxHealth?.let { addon.health.registerDefinition(key, HealthDefinition(it)) }
            val prepared = initializers + HealthInitializer(addon.health)
            addon.registerDefinition(CitadelDefinition(key, prepared))
            actions.validateDamage(citadels, key, allowed = authorizeDamage)
        }

        fun load() { manager.loadChunk(WORLD, 0, 0).join() }

        fun place(
            position: CitadelPosition = POSITION,
            key: Key = DEFAULT_KIND,
            context: ActionContext = PLACER_CONTEXT
        ): ActionResult = actions.execute(PlaceAction(context, position, key))

        fun damage(context: ActionContext, damage: Double, position: CitadelPosition = POSITION): ActionResult =
            actions.execute(DamageAction(context, position, damage))

        fun destroy(context: ActionContext, position: CitadelPosition = POSITION): ActionResult =
            actions.execute(DamageAction(context, position, 0.0, DamageAction.Mode.DESTROY))

        fun remove(context: ActionContext, position: CitadelPosition = POSITION): ActionResult =
            actions.execute(RemoveAction(context, position))

        fun find(position: CitadelPosition = POSITION) =
            manager.findCitadel(position.world, position.x, position.y, position.z)

        fun handle(position: CitadelPosition = POSITION) = assertNotNull(find(position))
        fun health(position: CitadelPosition = POSITION) = assertNotNull(handle(position).getComponent(Health::class.java))
        fun chunk() = assertNotNull(storage.getIfChunkLoaded(WXZCord(WORLD, 0, 0)))

        fun <T : Action> reject(type: Class<T>) {
            actions.registerValidator(object : ActionValidator<T> {
                override val actionClass = type
                override fun validate(action: T) = ActionValidator.Result.Rejected
            })
        }

        fun registerProbe(handler: (ProbeAction) -> ActionResult) {
            actions.registerProcessor(object : ActionProcessor<ProbeAction>() {
                override val actionClass = ProbeAction::class.java
                override val actionKey = PROBE_KEY
                override fun execute(action: ProbeAction): ActionResult = handler(action)
            })
        }
    }

    private class ProbeAction(val number: Int) : Action(PROBE_KEY, WORLD, 0, 0, 0)

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
