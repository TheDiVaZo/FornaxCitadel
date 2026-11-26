package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.addon.CoreGameplay
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolver
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.Action
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoreActionValidatorsTest {
    @Test
    fun `invalid core actions never reach a processor despite an earlier approval`() {
        val fixture = Fixture(approve = true)
        fixture.recordSuccessfulActions()
        for (position in listOf(POSITION.copy(y = -65), POSITION.copy(x = 524288), UNLOADED)) {
            for (action in targetActions(position) + PlaceAction(ActionContext.SYSTEM, position, TYPE)) {
                assertFalse(fixture.actions.isAllowed(action))
                assertEquals(ActionResult.DENIED, fixture.actions.execute(action))
            }
        }
        for (action in targetActions(POSITION)) {
            assertEquals(ActionResult.DENIED, fixture.actions.execute(action))
        }
        assertEquals(ActionResult.DENIED, fixture.actions.execute(
            PlaceAction(ActionContext.SYSTEM, POSITION, Key("test", "unknown"))
        ))
        fixture.create()
        assertEquals(ActionResult.DENIED, fixture.actions.execute(
            PlaceAction(ActionContext.SYSTEM, POSITION, TYPE)
        ))
        assertTrue(fixture.completed.isEmpty())
    }

    @Test
    fun `placement also requires a loaded and unoccupied responsible position`() {
        val fixture = Fixture(approve = true)
        fixture.recordSuccessfulActions()
        fixture.responsible = UNLOADED
        val action = PlaceAction(ActionContext.SYSTEM, POSITION, TYPE)
        assertEquals(ActionResult.DENIED, fixture.actions.execute(action))

        fixture.responsible = SECOND
        fixture.create(SECOND)
        assertEquals(ActionResult.DENIED, fixture.actions.execute(action))
        assertTrue(fixture.completed.isEmpty())
    }

    @Test
    fun `valid prerequisites remain neutral and do not authorize protected actions`() {
        val fixture = Fixture()
        fixture.create()
        fixture.recordSuccessfulActions()
        for (action in targetActions(POSITION)) {
            assertFalse(fixture.actions.isAllowed(action))
            assertEquals(ActionResult.DENIED, fixture.actions.execute(action))
        }
        assertTrue(fixture.completed.isEmpty())
        val place = PlaceAction(ActionContext.SYSTEM, SECOND, TYPE)
        assertTrue(fixture.actions.isAllowed(place))
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(place))
        assertEquals(listOf<Action>(place), fixture.completed)
        val unrelated = Action(Key("test", "unrelated"), WORLD, Int.MAX_VALUE, Int.MIN_VALUE, 0)
        val anotherPlace = PlaceAction(ActionContext.SYSTEM, POSITION.copy(x = 3), TYPE)
        for (intent in targetActions(POSITION) + listOf(anotherPlace, unrelated)) {
            // A neutral result follows both metadata settings. An explicit approval or rejection cannot.
            for (allowsNeutral in listOf(false, true)) {
                fixture.actions.registerActionMeta(intent.type, ActionMeta(allowsNeutral))
                assertEquals(allowsNeutral, fixture.actions.isAllowed(intent))
            }
        }
    }

    @Test
    fun `missing health passes validation and is handled by the damage service`() {
        val fixture = Fixture()
        val target = fixture.create(withHealth = false)
        var calculations = 0
        fixture.addon.health.registerModifier(DamageModifier { _, _, _ ->
            calculations++
            0.0
        })

        val damage = DamageAction(ActionContext.SYSTEM, POSITION, 1.0)
        val destroy = DamageAction(ActionContext.SYSTEM, POSITION, 0.0, DamageAction.Mode.DESTROY)
        assertFalse(fixture.actions.isAllowed(damage))
        assertFalse(fixture.actions.isAllowed(destroy))
        fixture.approve()
        assertTrue(fixture.actions.isAllowed(damage))
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(damage))
        assertFalse(target.isCurrent)
        assertEquals(1, calculations)
        val replacement = fixture.create(withHealth = false)
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(destroy))
        assertFalse(replacement.isCurrent)
        assertEquals(1, calculations)
    }

    @Test
    fun `queued actions validate availability when drained instead of when posted`() {
        val fixture = Fixture(approve = true)
        fixture.create()
        fixture.recordSuccessfulActions()
        fixture.actions.post(RemoveAction(ActionContext.SYSTEM, POSITION))
        val chunk = fixture.storage.chunk
        fixture.storage.chunk = null

        assertEquals(1, fixture.actions.drainPosted())
        assertTrue(fixture.completed.isEmpty())

        fixture.storage.chunk = chunk
        val damage = DamageAction(ActionContext.SYSTEM, SECOND, 1.0)
        fixture.actions.post(damage)
        fixture.create(SECOND)

        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(listOf<Action>(damage), fixture.completed)
    }

    private class Fixture(approve: Boolean = false) {
        val storage = MemoryStorage()
        val manager = ComponentManager(storage, TypeManager())
        var responsible = POSITION
        private val resolver = object : CitadelResolver {
            override fun getResponsiblePosition(position: CitadelPosition) =
                if (position == POSITION) responsible else position

            override fun getProtectionCandidates(position: CitadelPosition) = sequenceOf(position)
        }
        val actions = ActionManager()
        val citadels = CitadelFacade(manager, resolver)
        val addon: CoreGameplay
        val completed = mutableListOf<Action>()

        init {
            if (approve) approve()
            addon = CoreGameplay(citadels, ActionFacade(actions))
            addon.registerDefinition(CitadelDefinition(TYPE, emptyList()))
        }

        fun create(position: CitadelPosition = POSITION, withHealth: Boolean = true): CitadelHandle {
            val draft = MutableCitadelDraft(position).apply {
                addComponent(CitadelKind::class.java, CitadelKind(TYPE))
                if (withHealth) addComponent(ru.fornax.fornaxcitadel.modules.health.Health::class.java, ru.fornax.fornaxcitadel.modules.health.Health(20.0))
            }
            assertEquals(ActionResult.SUCCESS, manager.createCitadel(draft))
            return assertNotNull(manager.findCitadel(position.world, position.x, position.y, position.z))
        }

        fun approve(): ActionValidator<Action> = object : ActionValidator<Action> {
            override val actionClass = Action::class.java
            override fun validate(action: Action) = Result.Success
        }.also(actions::registerValidator)

        fun recordSuccessfulActions() {
            actions.registerHandler(object : ActionHandler<Action> {
                override val actionClass = Action::class.java
                override fun handle(action: Action) { completed.add(action) }
            })
        }
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

        fun targetActions(position: CitadelPosition): List<Action> = listOf(
            DamageAction(ActionContext.SYSTEM, position, 1.0),
            DamageAction(ActionContext.SYSTEM, position, 0.0, DamageAction.Mode.DESTROY),
            AcidDestroyAction(ActionContext.SYSTEM, position, POSITION, 0),
            RemoveAction(ActionContext.SYSTEM, position)
        )
    }
}
