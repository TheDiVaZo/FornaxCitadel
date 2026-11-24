package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolver
import ru.fornax.fornaxcitadel.core.resolver.DirectCitadelResolver
import ru.fornax.fornaxcitadel.core.scheduler.ManualScheduler
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionMeta
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator.Result
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.AcidDestroyAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.DamageModifier
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoreActionValidatorsTest {
    @Test
    fun `invalid core actions never reach a processor despite an earlier approval`() {
        Fixture(approve = true).use { fixture ->
            fixture.recordSuccessfulActions()
            for (position in listOf(POSITION.copy(y = -65), POSITION.copy(x = 524288), UNLOADED)) {
                for (action in targetActions(position) + PlaceAction(ActionContext.SYSTEM, position, TYPE)) {
                    assertFalse(fixture.module.actions.isAllowed(action))
                    assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))
                }
            }
            for (action in targetActions(POSITION)) {
                assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))
            }
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
                PlaceAction(ActionContext.SYSTEM, POSITION, Key("test", "unknown"))
            ))
            fixture.create()
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
                PlaceAction(ActionContext.SYSTEM, POSITION, TYPE)
            ))
            assertTrue(fixture.completed.isEmpty())
        }
    }

    @Test
    fun `placement also requires a loaded and unoccupied responsible position`() {
        Fixture(approve = true).use { fixture ->
            fixture.recordSuccessfulActions()
            fixture.responsible = UNLOADED
            val action = PlaceAction(ActionContext.SYSTEM, POSITION, TYPE)
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))

            fixture.responsible = SECOND
            fixture.create(SECOND)
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))
            assertTrue(fixture.completed.isEmpty())
        }
    }

    @Test
    fun `valid prerequisites remain neutral and do not authorize protected actions`() {
        Fixture().use { fixture ->
            fixture.create()
            fixture.recordSuccessfulActions()
            for (action in targetActions(POSITION)) {
                assertFalse(fixture.module.actions.isAllowed(action))
                assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))
            }
            assertTrue(fixture.completed.isEmpty())
            val place = PlaceAction(ActionContext.SYSTEM, SECOND, TYPE)
            assertTrue(fixture.module.actions.isAllowed(place))
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(place))
            assertEquals(listOf<ActionData>(place), fixture.completed)
            val unrelated = ActionData(Key("test", "unrelated"), WORLD, Int.MAX_VALUE, Int.MIN_VALUE, 0)
            val anotherPlace = PlaceAction(ActionContext.SYSTEM, POSITION.copy(x = 3), TYPE)
            for (intent in targetActions(POSITION) + listOf(anotherPlace, unrelated)) {
                // A neutral result follows both metadata settings. An explicit approval or rejection cannot.
                for (allowsNeutral in listOf(false, true)) {
                    fixture.module.actions.registerActionMeta(intent.type, ActionMeta(allowsNeutral))
                    assertEquals(allowsNeutral, fixture.module.actions.isAllowed(intent))
                }
            }
        }
    }

    @Test
    fun `missing health passes validation and is handled by the damage service`() {
        Fixture().use { fixture ->
            val target = fixture.create(withHealth = false)
            var calculations = 0
            fixture.module.health.registerModifier(DamageModifier { _, _, _ ->
                calculations++
                0.0
            })

            val damage = DamageAction(ActionContext.SYSTEM, POSITION, 1.0)
            val destroy = DamageAction(ActionContext.SYSTEM, POSITION, 0.0, DamageAction.Mode.DESTROY)
            assertFalse(fixture.module.actions.isAllowed(damage))
            assertFalse(fixture.module.actions.isAllowed(destroy))
            fixture.approve()
            assertTrue(fixture.module.actions.isAllowed(damage))
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(damage))
            assertFalse(target.isCurrent)
            assertEquals(1, calculations)
            val replacement = fixture.create(withHealth = false)
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(destroy))
            assertFalse(replacement.isCurrent)
            assertEquals(1, calculations)
        }
    }

    @Test
    fun `queued actions validate availability when drained instead of when posted`() {
        Fixture(approve = true).use { fixture ->
            fixture.create()
            fixture.recordSuccessfulActions()
            fixture.module.actions.post(RemoveAction(ActionContext.SYSTEM, POSITION))
            val chunk = fixture.storage.chunk
            fixture.storage.chunk = null

            assertEquals(1, fixture.module.actions.drainPosted())
            assertTrue(fixture.completed.isEmpty())

            fixture.storage.chunk = chunk
            val damage = DamageAction(ActionContext.SYSTEM, SECOND, 1.0)
            fixture.module.actions.post(damage)
            fixture.create(SECOND)

            assertEquals(1, fixture.module.actions.drainPosted())
            assertEquals(listOf<ActionData>(damage), fixture.completed)
        }
    }

    @Test
    fun `module lifecycle removes service prerequisites and restores them on enable`() {
        Fixture(enable = false).use { fixture ->
            val external = fixture.approve()
            val action = RemoveAction(ActionContext.SYSTEM, POSITION)
            assertTrue(fixture.module.actions.isAllowed(action))
            repeat(2) { fixture.module.enable() }
            assertFalse(fixture.module.actions.isAllowed(action))
            repeat(2) { fixture.module.disable() }
            assertTrue(fixture.module.actions.isAllowed(action))
            assertTrue(fixture.module.actions.unregisterValidator(external))
            assertFalse(fixture.module.actions.isAllowed(action))
            fixture.module.enable()
            assertFalse(fixture.module.actions.isAllowed(action))
        }
    }

    @Test
    fun `failed enable rolls back prerequisite validation without removing external validators`() {
        Fixture(enable = false).use { fixture ->
            fixture.approve()
            fixture.scheduler.timerFailure = IllegalStateException("Scheduling failed")
            val action = RemoveAction(ActionContext.SYSTEM, POSITION)

            assertFailsWith<IllegalStateException> { fixture.module.enable() }
            assertTrue(fixture.module.actions.isAllowed(action))
            fixture.scheduler.timerFailure = null
            fixture.module.enable()
            assertFalse(fixture.module.actions.isAllowed(action))
        }
    }

    private class Fixture(approve: Boolean = false, enable: Boolean = true) : AutoCloseable {
        val storage = MemoryStorage()
        val manager = ComponentManager(storage, TypeManager())
        val scheduler = ManualScheduler()
        var responsible = POSITION
        private val direct = DirectCitadelResolver(manager)
        private val resolver = object : CitadelResolver {
            override fun getResponsiblePosition(position: CitadelPosition) =
                if (position == POSITION) responsible else position

            override fun getReinforcementProtecting(position: CitadelPosition): CitadelHandle? =
                direct.getReinforcementProtecting(position)
        }
        val module = CitadelModule(manager, scheduler, resolver = resolver)
        val completed = mutableListOf<ActionData>()

        init {
            module.registerDefinition(CitadelDefinition(TYPE, emptyList()))
            if (approve) approve()
            if (enable) module.enable()
        }

        fun create(position: CitadelPosition = POSITION, withHealth: Boolean = true): CitadelHandle {
            val draft = MutableCitadelDraft(position).apply {
                addComponent(CitadelKind::class.java, CitadelKind(TYPE))
                if (withHealth) addComponent(ru.fornax.fornaxcitadel.modules.health.Health::class.java, ru.fornax.fornaxcitadel.modules.health.Health(20.0))
            }
            assertEquals(ActionResult.SUCCESS, manager.createCitadel(draft))
            return assertNotNull(manager.findCitadel(position.world, position.x, position.y, position.z))
        }

        fun approve(): ActionValidator<ActionData> = object : ActionValidator<ActionData> {
            override val actionClass = ActionData::class.java
            override fun validate(action: ActionData) = Result.Success
        }.also(module.actions::registerValidator)

        fun recordSuccessfulActions() {
            module.actions.registerHandler(object : ActionHandler<ActionData> {
                override val actionClass = ActionData::class.java
                override fun handle(action: ActionData) { completed.add(action) }
            })
        }

        override fun close() = module.disable()
    }

    private class MemoryStorage : CitadelStorage {
        var chunk: ChunkCord? = ChunkCord(WXZCord(WORLD, 0, 0))
        override fun getIfChunkLoaded(location: WXZCord) = chunk?.takeIf { it.location == location }
        override fun loadChunk(location: WXZCord): Nothing = error("Unexpected chunk load")
        override fun unloadChunk(location: WXZCord): Nothing = error("Unexpected chunk unload")
        override fun updateOpaque(chunk: ChunkCord): Nothing = error("Unexpected opaque update")
        override fun updateOpaque(key: Key): Nothing = error("Unexpected opaque update")
        override fun saveAll(): Nothing = error("Unexpected save")
        override fun closeAndSaveAll(): Nothing = error("Unexpected close")
    }

    private companion object {
        val WORLD = UUID(1L, 2L)
        val POSITION = CitadelPosition(WORLD, 1, 64, 2)
        val SECOND = POSITION.copy(x = 2)
        val UNLOADED = POSITION.copy(x = 16)
        val TYPE = Key("test", "reinforced")

        fun targetActions(position: CitadelPosition): List<ActionData> = listOf(
            DamageAction(ActionContext.SYSTEM, position, 1.0),
            DamageAction(ActionContext.SYSTEM, position, 0.0, DamageAction.Mode.DESTROY),
            AcidDestroyAction(ActionContext.SYSTEM, position, POSITION, 0),
            RemoveAction(ActionContext.SYSTEM, position)
        )
    }
}
