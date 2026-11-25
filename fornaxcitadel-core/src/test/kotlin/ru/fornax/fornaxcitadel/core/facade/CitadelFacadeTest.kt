package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.ReadableComponent
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolver
import ru.fornax.fornaxcitadel.core.resolver.DirectCitadelResolver
import ru.fornax.fornaxcitadel.core.scheduler.ManualScheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.DBStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.validateDamage
import ru.fornax.fornaxcitadel.modules.validateRemoval
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthDefinition
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import java.io.DataInput
import java.io.DataOutput
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

class CitadelFacadeTest {
    @Test
    fun `default facade keeps exact coordinates and distinguishes unavailable data`() {
        Fixture(useRules = false).use { fixture ->
            val facade = fixture.module.citadels
            assertNull(facade.findProtecting(TOP))
            val placed = assertNotNull(facade.createCitadel(TOP, fixture::fill))
            assertEquals(TOP, placed.position)
            assertNull(facade.findProtecting(BOTTOM))
            assertEquals(placed.runtimeId, facade.findProtecting(TOP)?.runtimeId)
            assertFailsWith<CitadelResolutionUnavailableException> {
                facade.findProtecting(OTHER_CHUNK)
            }
            assertFalse(fixture.manager.isChunkLoaded(WORLD, 1, 0))
        }
    }

    @Test
    fun `exact lookup finds only the stored half of a linked block`() {
        Fixture().use { fixture ->
            val facade = fixture.module.citadels
            val bottom = fixture.createExact(BOTTOM)

            assertEquals(bottom.runtimeId, facade.findProtecting(TOP)?.runtimeId)
            assertNull(facade.findExact(TOP))
            assertEquals(bottom.runtimeId, facade.findExact(BOTTOM)?.runtimeId)
            assertNull(facade.findExact(TOP.copy(y = -65)))
            assertNull(facade.findExact(OTHER_CHUNK))
            assertFalse(facade.isChunkLoaded(WORLD, 1, 0))
        }
    }

    @Test
    fun `creation fills one unpublished draft at the resolved position and consumes it`() {
        Fixture().use { fixture ->
            var fills = 0
            lateinit var retainedDraft: CitadelDraft
            val placed = assertNotNull(fixture.module.citadels.createCitadel(TOP) { draft ->
                fills++
                retainedDraft = draft
                assertEquals(BOTTOM, draft.position)
                assertNull(fixture.exact(TOP))
                assertNull(fixture.exact(BOTTOM))
                fixture.fill(draft)
            })
            assertEquals(1, fills)
            assertEquals(1, fixture.responsibleLookups)
            assertEquals(BOTTOM, placed.position)
            assertEquals(100.0, placed.getComponent(Health::class.java)?.health)
            assertNull(fixture.exact(TOP))
            assertFailsWith<IllegalStateException> { retainedDraft.getComponent(Health::class.java) }
        }
    }

    @Test
    fun `early rejection never invokes initialization for either occupied half or unavailable data`() {
        for (occupied in listOf(TOP, BOTTOM)) {
            Fixture().use { fixture ->
                fixture.createExact(occupied)
                assertNull(fixture.module.citadels.createCitadel(TOP) { error("Must not initialize") })
                assertNull(fixture.module.citadels.createCitadel(BOTTOM) { error("Must not initialize") })
            }
        }
        Fixture().use { fixture ->
            fixture.target = OTHER_CHUNK
            assertNull(fixture.module.citadels.createCitadel(TOP) { error("Must not initialize") })
            assertNull(fixture.module.citadels.createCitadel(TOP.copy(y = -65)) { error("Must not initialize") })
            assertFalse(fixture.manager.isChunkLoaded(WORLD, 1, 0))
        }
    }

    @Test
    fun `initializer and component validation failures publish nothing`() {
        Fixture().use { fixture ->
            assertFailsWith<IllegalStateException> {
                fixture.module.citadels.createCitadel(TOP) { draft ->
                    fixture.fill(draft)
                    error("Initializer failed")
                }
            }
            assertFailsWith<IllegalArgumentException> {
                fixture.module.citadels.createCitadel(TOP) { draft ->
                    draft.addComponent(UnregisteredComponent::class.java, UnregisteredComponent())
                }
            }
            assertNull(fixture.exact(BOTTOM))
            assertTrue(fixture.chunk().isEmpty)
            assertFalse(fixture.chunk().isDirty)
        }
    }

    @Test
    fun `commit rechecks conflicts without choosing a new responsible position`() {
        Fixture().use { fixture ->
            var conflicting: CitadelHandle? = null
            assertNull(fixture.module.citadels.createCitadel(TOP) { draft ->
                fixture.fill(draft)
                // Deliberately violate the initializer contract to exercise the final storage guard.
                conflicting = fixture.createExact(BOTTOM)
            })
            assertEquals(1, fixture.responsibleLookups)
            assertTrue(assertNotNull(conflicting).isCurrent)
            assertEquals(conflicting.runtimeId, fixture.exact(BOTTOM)?.runtimeId)
        }
    }

    @Test
    fun `raw placement damage and removal resolve protection without rewriting actions`() {
        Fixture().use { fixture ->
            val initialized = mutableListOf<CitadelPosition>()
            val placed = mutableListOf<PlaceAction>()
            fixture.module.coreAddon.health.registerDefinition(CUSTOM_TYPE, HealthDefinition(100.0))
            fixture.module.registerDefinition(CitadelDefinition(CUSTOM_TYPE, listOf(
                CitadelInitializer { _, draft -> initialized.add(draft.position) }, HealthInitializer(fixture.module.coreAddon.health)
            )))
            fixture.module.actions.validateDamage(fixture.module.citadels, CUSTOM_TYPE)
            fixture.module.actions.registerHandler(object : ActionHandler<PlaceAction> {
                override val actionClass = PlaceAction::class.java
                override fun handle(action: PlaceAction) { placed.add(action) }
            })
            val placement = PlaceAction(CONTEXT, TOP, CUSTOM_TYPE)
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(placement))
            assertEquals(listOf(BOTTOM), initialized)
            assertSame(placement, placed.single())
            assertEquals(TOP, placed.single().position)
            assertNotNull(fixture.exact(BOTTOM))

            val request = SpecializedDamage(TOP, 7.0)
            var seen = false
            fixture.module.actions.registerValidator(object : ActionValidator<SpecializedDamage> {
                override val actionClass = SpecializedDamage::class.java
                override fun validate(action: SpecializedDamage): ActionValidator.Result {
                    assertSame(request, action)
                    assertEquals(TOP, action.position)
                    seen = true
                    return ActionValidator.Result.Neutral
                }
            })
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(request))
            assertTrue(seen)
            assertEquals(93.0, fixture.health(BOTTOM))
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(RemoveAction(CONTEXT, TOP)))
            assertNull(fixture.exact(BOTTOM))
        }
    }

    @Test
    fun `posted actions resolve current protection when drained`() {
        Fixture().use { fixture ->
            fixture.createExact(BOTTOM)
            val alternative = BOTTOM.copy(x = 3)
            fixture.createExact(alternative)
            fixture.module.actions.post(DamageAction(CONTEXT, TOP, 9.0))
            fixture.target = alternative
            fixture.scheduler.tick()
            assertEquals(100.0, fixture.health(BOTTOM))
            assertEquals(91.0, fixture.health(alternative))
        }
    }

    @Test
    fun `damage validators apply to resolved reinforcement when queued actions execute`() {
        Fixture().use { fixture ->
            fixture.createExact(BOTTOM, BLOCKED_TYPE)
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(DamageAction(CONTEXT, TOP, 9.0)))
            assertEquals(100.0, fixture.health(BOTTOM))
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(
                DamageAction(CONTEXT, TOP, 0.0, DamageAction.Mode.DESTROY)
            ))
            fixture.createExact(BOTTOM)
            val blocked = BOTTOM.copy(x = 3)
            fixture.createExact(blocked, BLOCKED_TYPE)
            fixture.module.actions.post(DamageAction(CONTEXT, TOP, 9.0))
            fixture.target = blocked
            assertEquals(1, fixture.module.actions.drainPosted())
            assertEquals(100.0, fixture.health(BOTTOM))
            assertEquals(100.0, fixture.health(blocked))
        }
    }

    @Test
    fun `unavailable related data rejects all core actions without initializing or loading`() {
        Fixture().use { fixture ->
            fixture.target = OTHER_CHUNK
            val module = fixture.module
            assertEquals(ActionResult.DENIED, module.actions.execute(PlaceAction(CONTEXT, TOP, TYPE)))
            assertEquals(ActionResult.DENIED, module.actions.execute(DamageAction(CONTEXT, TOP, 1.0)))
            assertEquals(ActionResult.DENIED, module.actions.execute(RemoveAction(CONTEXT, TOP)))
            assertFalse(fixture.manager.isChunkLoaded(WORLD, 1, 0))
            assertTrue(fixture.chunk().isEmpty)
        }
    }

    @Test
    fun `legacy local reinforcement wins and direct handle removal remains exact`() {
        Fixture().use { fixture ->
            val top = fixture.createExact(TOP)
            val bottom = fixture.createExact(BOTTOM)
            assertEquals(top.runtimeId, fixture.module.citadels.findProtecting(TOP)?.runtimeId)
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(DamageAction(CONTEXT, TOP, 5.0)))
            assertEquals(95.0, fixture.health(TOP))
            assertEquals(100.0, fixture.health(BOTTOM))
            assertEquals(ActionResult.SUCCESS, fixture.module.coreAddon.remove.remove(top))
            assertEquals(bottom.runtimeId, fixture.module.citadels.findProtecting(TOP)?.runtimeId)
            assertEquals(ActionResult.DENIED, fixture.module.coreAddon.remove.remove(top))
            assertTrue(bottom.isCurrent)
        }
    }

    private class Fixture(useRules: Boolean = true) : AutoCloseable {
        private val types = TypeManager()
        val scheduler = ManualScheduler()
        private val storage = ChunkBackendLifecycleCoordinator(MemoryBackend(), scheduler,
            ChunkCordSnapshotMapper(types), Executor(Runnable::run))
        val manager = ComponentManager(storage, types)
        var target = BOTTOM
        var responsibleLookups = 0
        private val direct = DirectCitadelResolver(manager)
        private val resolver = object : CitadelResolver {
            override fun getResponsiblePosition(position: CitadelPosition): CitadelPosition {
                responsibleLookups++
                return if (position == TOP) target else position
            }

            override fun getReinforcementProtecting(position: CitadelPosition): CitadelHandle? {
                direct.getReinforcementProtecting(position)?.let { return it }
                val related = when (position) {
                    TOP -> target
                    target -> TOP
                    else -> return null
                }
                return direct.getReinforcementProtecting(related)
            }
        }
        val module = CitadelModule(manager, scheduler, resolver = if (useRules) resolver else direct)

        init {
            module.coreAddon.health.registerDefinition(TYPE, HealthDefinition(100.0))
            module.coreAddon.health.registerDefinition(BLOCKED_TYPE, HealthDefinition(100.0))
            module.registerDefinition(CitadelDefinition(TYPE, listOf(HealthInitializer(module.coreAddon.health))))
            module.actions.validateDamage(module.citadels, TYPE)
            module.registerDefinition(CitadelDefinition(BLOCKED_TYPE, listOf(HealthInitializer(module.coreAddon.health))))
            module.actions.validateDamage(module.citadels, BLOCKED_TYPE) { _, _ -> false }
            module.actions.validateDamage(module.citadels, mode = DamageAction.Mode.DESTROY)
            module.actions.validateRemoval(module.citadels)
            module.coreAddon.onEnable()
            module.enable()
            manager.loadChunk(WORLD, 0, 0).join()
        }

        fun fill(draft: CitadelDraft) {
            draft.addComponent(CitadelKind::class.java, CitadelKind(TYPE))
            HealthInitializer(module.coreAddon.health).initialize(CONTEXT, draft)
        }

        fun createExact(position: CitadelPosition, type: Key = TYPE): CitadelHandle {
            val draft = MutableCitadelDraft(position)
            draft.addComponent(CitadelKind::class.java, CitadelKind(type))
            HealthInitializer(module.coreAddon.health).initialize(CONTEXT, draft)
            assertEquals(ActionResult.SUCCESS, manager.createCitadel(draft))
            return assertNotNull(exact(position))
        }

        fun exact(position: CitadelPosition): CitadelHandle? =
            manager.findCitadel(position.world, position.x, position.y, position.z)

        fun health(position: CitadelPosition): Double =
            assertNotNull(assertNotNull(exact(position)).getComponent(Health::class.java)).health

        fun chunk() = assertNotNull(storage.getIfChunkLoaded(WXZCord(WORLD, 0, 0)))

        override fun close() {
            module.disable()
            module.coreAddon.onDisable()
            manager.closeAndSaveAll().join()
        }
    }

    private class SpecializedDamage(position: CitadelPosition, amount: Double) : DamageAction(CONTEXT, position, amount)

    private class UnregisteredComponent : Component<ReadableComponent>(), ReadableComponent {
        override val readable: ReadableComponent get() = this
        override fun write(output: DataOutput) = Unit
        override fun read(input: DataInput) = Unit
    }

    private class MemoryBackend : DBStorage {
        override fun loadChunk(location: WXZCord): CompletableFuture<ChunkCordSnapshot?> =
            CompletableFuture.completedFuture(null)
        override fun saveChunk(snapshot: ChunkCordSnapshot): CompletableFuture<Void> =
            CompletableFuture.completedFuture(null)
    }

    private companion object {
        val WORLD = UUID(1L, 2L)
        val TOP = CitadelPosition(WORLD, 1, 65, 2)
        val BOTTOM = CitadelPosition(WORLD, 1, 64, 2)
        val OTHER_CHUNK = CitadelPosition(WORLD, 16, 64, 2)
        val TYPE = Key("test", "reinforced")
        val CUSTOM_TYPE = Key("test", "initialized")
        val BLOCKED_TYPE = Key("test", "invulnerable")
        val CONTEXT = ActionContext.SYSTEM
    }
}
