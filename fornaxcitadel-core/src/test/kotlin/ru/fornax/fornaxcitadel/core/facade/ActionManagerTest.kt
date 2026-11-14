package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionMetaRegistry.ActionMeta
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator.Result
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
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

    @Test
    fun `handlers receive the completed action in registration order including base class handlers`() {
        val fixture = Fixture()
        val calls = mutableListOf<String>()
        val received = mutableListOf<ActionData>()
        val action = SpecialAction()
        fixture.validate { calls.add("validate"); Result.Success }
        fixture.process = { calls.add("process"); ActionResult.SUCCESS }
        fixture.actions.handlerCoordinator.registerHandler(handler(ActionData::class.java) {
            calls.add("base")
            received.add(it)
        })
        fixture.actions.handlerCoordinator.registerHandler(handler(OtherAction::class.java) {
            calls.add("unrelated")
        })
        fixture.actions.handlerCoordinator.registerHandler(handler(SpecialAction::class.java) {
            calls.add("specific")
            received.add(it)
        })

        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(action))
        assertEquals(listOf("validate", "process", "base", "specific"), calls)
        assertEquals(2, received.size)
        received.forEach { assertSame(action, it) }
    }

    @Test
    fun `denied actions do not invoke handlers regardless of where execution stops`() {
        val cases: List<(Fixture) -> ActionData> = listOf(
            { it.validate { Result.Rejected }; it.action() },
            { it.actions.actionMetaRegistry.defaultMeta = ActionMeta(false); it.action() },
            { it.action(Key("test", "missing_processor")) },
            { it.process = { ActionResult.DENIED }; it.action() }
        )
        for (configure in cases) {
            val fixture = Fixture()
            val received = mutableListOf<ActionData>()
            fixture.actions.handlerCoordinator.registerHandler(handler(ActionData::class.java, received::add))
            val action = configure(fixture)

            assertEquals(ActionResult.DENIED, fixture.actions.execute(action))
            assertTrue(received.isEmpty())
        }
    }

    @Test
    fun `validator and processor exceptions skip handlers and release the execution guard`() {
        for (failInValidator in listOf(true, false)) {
            val fixture = Fixture()
            val failure = IllegalStateException("Cannot finish action")
            var fail = true
            fixture.validate {
                if (fail && failInValidator) throw failure
                Result.Success
            }
            fixture.process = {
                if (fail && !failInValidator) throw failure
                ActionResult.SUCCESS
            }
            val received = mutableListOf<ActionData>()
            fixture.actions.handlerCoordinator.registerHandler(handler(ActionData::class.java, received::add))

            assertSame(failure, assertFailsWith<IllegalStateException> { fixture.actions.execute(fixture.action()) })
            assertTrue(received.isEmpty())

            fail = false
            val retry = fixture.action()
            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(retry))
            assertEquals(listOf(retry), received)
        }
    }

    @Test
    fun `handler exceptions preserve success and do not skip later handlers or subsequent actions`() {
        val fixture = Fixture()
        var failures = 0
        val received = mutableListOf<ActionData>()
        fixture.actions.handlerCoordinator.registerHandler(handler(ActionData::class.java) {
            failures++
            error("Broken handler")
        })
        fixture.actions.handlerCoordinator.registerHandler(handler(ActionData::class.java, received::add))
        val first = fixture.action()
        val second = fixture.action()

        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(first))
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(second))

        assertEquals(2, failures)
        assertEquals(listOf(first, second), received)
        assertEquals(received, fixture.executed)
    }

    @Test
    fun `registration and unregistration use identity even when handlers compare equal`() {
        val fixture = Fixture()
        val coordinator = fixture.actions.handlerCoordinator
        val first = EqualHandler()
        val second = EqualHandler()
        coordinator.registerHandler(first)
        coordinator.registerHandler(first)
        coordinator.registerHandler(second)

        assertFalse(coordinator.unregisterHandler(EqualHandler()))
        fixture.actions.execute(fixture.action())
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)

        assertTrue(coordinator.unregisterHandler(first))
        assertFalse(coordinator.unregisterHandler(first))
        fixture.actions.execute(fixture.action())
        assertEquals(1, first.calls)
        assertEquals(2, second.calls)
    }

    @Test
    fun `handler registration changes take effect on the next dispatch`() {
        val fixture = Fixture()
        val coordinator = fixture.actions.handlerCoordinator
        val calls = mutableListOf<String>()
        val later = handler(ActionData::class.java) { calls.add("later") }
        val added = handler(ActionData::class.java) { calls.add("added") }
        lateinit var first: ActionHandler<ActionData>
        first = handler(ActionData::class.java) {
            calls.add("first")
            coordinator.unregisterHandler(first)
            coordinator.unregisterHandler(later)
            coordinator.registerHandler(added)
        }
        coordinator.registerHandler(first)
        coordinator.registerHandler(later)

        fixture.actions.execute(fixture.action())
        assertEquals(listOf("first", "later"), calls)
        calls.clear()
        fixture.actions.execute(fixture.action())
        assertEquals(listOf("added"), calls)
    }

    @Test
    fun `handlers can post work but cannot execute or drain until dispatch finishes`() {
        val fixture = Fixture()
        val first = fixture.action()
        val posted = fixture.action(OTHER_KEY)
        val blocked = mutableListOf<Boolean>()
        val received = mutableListOf<ActionData>()
        fixture.actions.handlerCoordinator.registerHandler(handler(ActionData::class.java) {
            if (it === first) {
                blocked.add(runCatching { fixture.actions.execute(posted) }.exceptionOrNull() is IllegalStateException)
                blocked.add(runCatching { fixture.actions.drainPosted() }.exceptionOrNull() is IllegalStateException)
                fixture.actions.post(posted)
            }
        })
        fixture.actions.handlerCoordinator.registerHandler(handler(ActionData::class.java, received::add))

        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(first))
        assertEquals(listOf(true, true), blocked)
        assertEquals(listOf(first), received)
        assertEquals(listOf(first), fixture.executed)

        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(listOf(first, posted), received)
        assertEquals(received, fixture.executed)
        assertEquals(0, fixture.actions.drainPosted())
    }

    @Test
    fun `calling processors directly does not dispatch action handlers`() {
        val fixture = Fixture()
        val received = mutableListOf<ActionData>()
        fixture.actions.handlerCoordinator.registerHandler(handler(ActionData::class.java, received::add))
        val action = fixture.action()

        assertEquals(ActionResult.SUCCESS, fixture.actions.processorCoordinator.execute(action))
        assertEquals(listOf(action), fixture.executed)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `direct handling also blocks execution and draining while allowing posted actions`() {
        val fixture = Fixture()
        val notification = fixture.action()
        val posted = fixture.action(OTHER_KEY)
        val blocked = mutableListOf<Boolean>()
        val received = mutableListOf<ActionData>()
        fixture.actions.handlerCoordinator.registerHandler(handler(ActionData::class.java) {
            if (it === notification) {
                blocked.add(runCatching { fixture.actions.execute(posted) }.exceptionOrNull() is IllegalStateException)
                blocked.add(runCatching { fixture.actions.drainPosted() }.exceptionOrNull() is IllegalStateException)
                fixture.actions.post(posted)
            }
        })
        fixture.actions.handlerCoordinator.registerHandler(handler(ActionData::class.java, received::add))

        fixture.actions.handlerCoordinator.handle(notification)

        assertEquals(listOf(true, true), blocked)
        assertEquals(listOf(notification), received)
        assertTrue(fixture.executed.isEmpty())

        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(listOf(posted), fixture.executed)
        assertEquals(listOf(notification, posted), received)
        assertEquals(0, fixture.actions.drainPosted())
    }

    private fun <T : ActionData> handler(actionClass: Class<T>, callback: (T) -> Unit): ActionHandler<T> =
        object : ActionHandler<T> {
            override val actionClass = actionClass
            override fun handle(action: T) = callback(action)
        }

    private class SpecialAction : ActionData(PROBE_KEY, UUID(0, 1), 0, 0, 0)
    private class OtherAction : ActionData(PROBE_KEY, UUID(0, 1), 0, 0, 0)

    private class EqualHandler : ActionHandler<ActionData> {
        override val actionClass = ActionData::class.java
        var calls = 0
        override fun handle(action: ActionData) { calls++ }
        override fun equals(other: Any?) = other is EqualHandler
        override fun hashCode() = 0
    }

    private class Fixture {
        val actions = ActionManager(ComponentManager(UnusedStorage, TypeManager()))
        val executed = mutableListOf<ActionData>()
        var process: (ActionData) -> ActionResult = { ActionResult.SUCCESS }

        init {
            for (key in listOf(PROBE_KEY, OTHER_KEY)) {
                actions.processorCoordinator.registerProcessor(object : ActionProcessor<ActionData>() {
                    override val actionClass = ActionData::class.java
                    override val actionKey = key
                    override fun execute(action: ActionData): ActionResult {
                        executed.add(action)
                        return process(action)
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
