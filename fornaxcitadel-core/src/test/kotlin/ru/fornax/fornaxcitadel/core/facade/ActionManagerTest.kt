package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionMetaRegistry.ActionMeta
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator.Result
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionManagerTest {
    @Test
    fun `the default executes actions without validators or with only neutral validators`() {
        for (validatorCount in listOf(0, 2)) {
            val fixture = Fixture()
            var validations = 0
            repeat(validatorCount) {
                fixture.validate { validations++; Result.Neutral }
            }
            val action = fixture.action()

            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(action))
            assertEquals(validatorCount, validations)
            assertEquals(listOf(action), fixture.executed)
        }
    }

    @Test
    fun `a denying default skips the processor when no validator grants approval`() {
        for (validatorCount in listOf(0, 2)) {
            val fixture = Fixture()
            fixture.actions.actionMetaRegistry.defaultMeta = ActionMeta(false)
            var validations = 0
            repeat(validatorCount) {
                fixture.validate { validations++; Result.Neutral }
            }

            assertEquals(ActionResult.DENIED, fixture.actions.execute(fixture.action()))
            assertEquals(validatorCount, validations)
            assertTrue(fixture.executed.isEmpty())
        }
    }

    @Test
    fun `metadata applies per key and unregistering restores the current default`() {
        for (defaultAllows in listOf(false, true)) {
            val fixture = Fixture()
            val registry = fixture.actions.actionMetaRegistry
            registry.defaultMeta = ActionMeta(defaultAllows)
            registry.register(PROBE_KEY, ActionMeta(!defaultAllows))
            val overrideAction = fixture.action(PROBE_KEY)
            val defaultAction = fixture.action(OTHER_KEY)

            assertEquals(result(!defaultAllows), fixture.actions.execute(overrideAction))
            assertEquals(result(defaultAllows), fixture.actions.execute(defaultAction))
            assertEquals(listOf(if (defaultAllows) defaultAction else overrideAction), fixture.executed)

            registry.unregister(PROBE_KEY)
            assertEquals(result(defaultAllows), fixture.actions.execute(overrideAction))
        }
    }

    @Test
    fun `success grants approval even when neutral execution is disabled`() {
        val fixture = Fixture()
        fixture.actions.actionMetaRegistry.register(PROBE_KEY, ActionMeta(false))
        fixture.validate { Result.Neutral }
        fixture.validate { Result.Success }
        fixture.validate { Result.Neutral }
        val action = fixture.action()

        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(action))
        assertEquals(listOf(action), fixture.executed)
    }

    @Test
    fun `rejection prevents execution regardless of success and neutral metadata`() {
        for (allowsNeutral in listOf(false, true)) {
            val fixture = Fixture()
            fixture.actions.actionMetaRegistry.register(PROBE_KEY, ActionMeta(allowsNeutral))
            fixture.validate { Result.Success }
            fixture.validate { Result.Rejected }

            assertEquals(ActionResult.DENIED, fixture.actions.execute(fixture.action()))
            assertTrue(fixture.executed.isEmpty())
        }
    }

    @Test
    fun `posted actions use metadata at execution time`() {
        val fixture = Fixture()
        val registry = fixture.actions.actionMetaRegistry
        fixture.actions.post(fixture.action())
        registry.register(PROBE_KEY, ActionMeta(false))

        assertEquals(1, fixture.actions.drainPosted())
        assertTrue(fixture.executed.isEmpty())

        val allowed = fixture.action()
        fixture.actions.post(allowed)
        registry.register(PROBE_KEY, ActionMeta(true))

        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(listOf(allowed), fixture.executed)
    }

    @Test
    fun `posted actions validate current authorization when drained`() {
        val fixture = Fixture()
        fixture.actions.actionMetaRegistry.register(PROBE_KEY, ActionMeta(false))
        var authorized = true
        var validations = 0
        fixture.validate {
            validations++
            if (authorized) Result.Success else Result.Rejected
        }
        fixture.actions.post(fixture.action())
        assertEquals(0, validations)
        authorized = false

        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(1, validations)
        assertTrue(fixture.executed.isEmpty())

        val allowed = fixture.action()
        fixture.actions.post(allowed)
        authorized = true

        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(2, validations)
        assertEquals(listOf(allowed), fixture.executed)
    }

    private class Fixture {
        val actions = ActionManager(ComponentManager(UnusedStorage, TypeManager()))
        val executed = mutableListOf<ActionData>()

        init {
            for (key in listOf(PROBE_KEY, OTHER_KEY)) {
                actions.processorCoordinator.registerProcessor(object : ActionProcessor<ActionData>() {
                    override val actionClass = ActionData::class.java
                    override val actionKey = key
                    override fun execute(action: ActionData): ActionResult {
                        executed.add(action)
                        return ActionResult.SUCCESS
                    }
                })
            }
        }

        fun action(key: Key = PROBE_KEY) = ActionData(key, UUID(0, 1), 0, 0, 0)

        fun validate(validation: () -> Result) {
            actions.validatorCoordinator.registerValidator(object : ActionValidator<ActionData> {
                override val actionClass = ActionData::class.java
                override fun validate(action: ActionData) = validation()
            })
        }
    }

    private object UnusedStorage : CitadelStorage {
        override fun getIfChunkLoaded(location: WXZCord): ChunkCord? = null
        override fun loadChunk(location: WXZCord): Nothing = error("Unexpected chunk load")
        override fun unloadChunk(location: WXZCord): Nothing = error("Unexpected chunk unload")
        override fun updateOpaque(chunk: ChunkCord): Nothing = error("Unexpected opaque update")
        override fun updateOpaque(key: Key): Nothing = error("Unexpected opaque update")
        override fun saveAll(): Nothing = error("Unexpected save")
        override fun closeAndSaveAll(): Nothing = error("Unexpected close")
    }

    private companion object {
        val PROBE_KEY = Key("test", "probe")
        val OTHER_KEY = Key("test", "other")
        fun result(allowed: Boolean) = if (allowed) ActionResult.SUCCESS else ActionResult.DENIED
    }
}
