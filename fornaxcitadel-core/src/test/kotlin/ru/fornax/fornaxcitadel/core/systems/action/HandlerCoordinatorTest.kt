package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HandlerCoordinatorTest {
    private val action = Action(Key("test", "action"), UUID(0L, 0L), 1, 2, 3)

    @Test
    fun `direct handling matches base and specific classes in registration order`() {
        val coordinator = HandlerCoordinator()
        coordinator.handle(action)
        val calls = mutableListOf<Pair<String, Action>>()
        var unrelatedCalls = 0
        coordinator.registerHandler(handler(OtherAction::class.java) { unrelatedCalls++ })
        coordinator.registerHandler(handler(Action::class.java) { calls.add("first base" to it) })
        coordinator.registerHandler(handler(SpecialAction::class.java) { calls.add("specific" to it) })
        coordinator.registerHandler(handler(Action::class.java) { calls.add("last base" to it) })
        val special = SpecialAction()

        coordinator.handle(special)

        assertEquals(listOf("first base", "specific", "last base"), calls.map { it.first })
        calls.forEach { assertSame(special, it.second) }
        assertEquals(0, unrelatedCalls)
        calls.clear()
        coordinator.handle(action)
        assertEquals(listOf("first base", "last base"), calls.map { it.first })
        calls.forEach { assertSame(action, it.second) }
        assertEquals(0, unrelatedCalls)
    }

    @Test
    fun `pending additions and removals leave every current callback in place`() {
        val coordinator = HandlerCoordinator()
        val calls = mutableListOf<String>()
        val later = handler(Action::class.java) { calls.add("later") }
        val added = handler(Action::class.java) { calls.add("added") }
        lateinit var first: ActionHandler<Action>
        first = handler(Action::class.java) {
            calls.add("first")
            assertTrue(coordinator.unregisterHandler(first))
            assertTrue(coordinator.unregisterHandler(later))
            coordinator.registerHandler(added)
        }
        coordinator.registerHandler(first)
        coordinator.registerHandler(later)

        coordinator.handle(action)

        assertEquals(listOf("first", "later"), calls)
        calls.clear()
        coordinator.handle(action)
        assertEquals(listOf("added"), calls)
        assertFalse(coordinator.unregisterHandler(first))
        assertFalse(coordinator.unregisterHandler(later))
    }

    @Test
    fun `pending membership honors duplicate additions repeated removals and add then remove`() {
        val coordinator = HandlerCoordinator()
        val calls = mutableListOf<String>()
        val retained = handler(Action::class.java) { calls.add("retained") }
        val added = handler(Action::class.java) { calls.add("added") }
        val temporary = handler(Action::class.java) { calls.add("temporary") }
        var change = true
        coordinator.registerHandler(handler(Action::class.java) {
            calls.add("first")
            if (change) {
                change = false
                coordinator.registerHandler(retained)
                coordinator.registerHandler(added)
                coordinator.registerHandler(added)
                coordinator.registerHandler(temporary)
                coordinator.registerHandler(temporary)
                assertTrue(coordinator.unregisterHandler(temporary))
                assertFalse(coordinator.unregisterHandler(temporary))
            }
        })
        coordinator.registerHandler(retained)

        coordinator.handle(action)

        assertEquals(listOf("first", "retained"), calls)
        assertFalse(coordinator.unregisterHandler(temporary))
        calls.clear()
        coordinator.handle(action)
        assertEquals(listOf("first", "retained", "added"), calls)
        assertTrue(coordinator.unregisterHandler(added))
        assertFalse(coordinator.unregisterHandler(added))
    }

    @Test
    fun `remove then register appends the same instance in deferred operation order`() {
        val coordinator = HandlerCoordinator()
        val calls = mutableListOf<String>()
        val moved = handler(Action::class.java) { calls.add("moved") }
        val retained = handler(Action::class.java) { calls.add("retained") }
        val added = handler(Action::class.java) { calls.add("added") }
        var change = true
        coordinator.registerHandler(handler(Action::class.java) {
            calls.add("first")
            if (change) {
                change = false
                assertTrue(coordinator.unregisterHandler(moved))
                assertFalse(coordinator.unregisterHandler(moved))
                coordinator.registerHandler(added)
                coordinator.registerHandler(moved)
                coordinator.registerHandler(moved)
            }
        })
        coordinator.registerHandler(moved)
        coordinator.registerHandler(retained)

        coordinator.handle(action)

        assertEquals(listOf("first", "moved", "retained"), calls)
        calls.clear()
        coordinator.handle(action)
        assertEquals(listOf("first", "retained", "added", "moved"), calls)
    }

    @Test
    fun `registration and pending membership compare handlers by identity`() {
        val coordinator = HandlerCoordinator()
        val calls = mutableListOf<String>()
        val first = EqualHandler("first", calls)
        val second = EqualHandler("second", calls)
        val added = EqualHandler("added", calls)
        var change = true
        coordinator.registerHandler(handler(Action::class.java) {
            if (change) {
                change = false
                assertFalse(coordinator.unregisterHandler(added))
                coordinator.registerHandler(added)
                coordinator.registerHandler(added)
                assertTrue(coordinator.unregisterHandler(first))
                assertFalse(coordinator.unregisterHandler(first))
            }
        })
        coordinator.registerHandler(first)
        coordinator.registerHandler(second)
        coordinator.registerHandler(first)
        assertEquals(first, second)

        coordinator.handle(action)

        assertEquals(listOf("first", "second"), calls)
        calls.clear()
        coordinator.handle(action)
        assertEquals(listOf("second", "added"), calls)
        assertFalse(coordinator.unregisterHandler(EqualHandler("unregistered", calls)))
        assertTrue(coordinator.unregisterHandler(second))
        calls.clear()
        coordinator.handle(action)
        assertEquals(listOf("added"), calls)
    }

    @Test
    fun `callback exceptions are logged while later callbacks run and pending changes are applied`() =
        withRecordedFailures { failures ->
            val coordinator = HandlerCoordinator()
            val calls = mutableListOf<String>()
            val failure = IllegalStateException("broken handler")
            val later = handler(Action::class.java) { calls.add("later") }
            val added = handler(Action::class.java) { calls.add("added") }
            lateinit var broken: ActionHandler<Action>
            broken = handler(Action::class.java) {
                calls.add("broken")
                assertTrue(coordinator.unregisterHandler(broken))
                assertTrue(coordinator.unregisterHandler(later))
                coordinator.registerHandler(added)
                throw failure
            }
            coordinator.registerHandler(broken)
            coordinator.registerHandler(later)

            coordinator.handle(action)

            assertSame(failure, failures.single())
            assertEquals(listOf("broken", "later"), calls)
            calls.clear()
            coordinator.handle(action)
            assertEquals(listOf("added"), calls)
            assertEquals(1, failures.size)
        }

    @Test
    fun `nested handling is rejected without changing the outer traversal or pending state`() {
        val coordinator = HandlerCoordinator()
        val calls = mutableListOf<String>()
        val later = handler(Action::class.java) { calls.add("later") }
        val added = handler(Action::class.java) { calls.add("added") }
        var recurse = true
        coordinator.registerHandler(handler(Action::class.java) {
            calls.add("first")
            if (recurse) {
                recurse = false
                assertFailsWith<IllegalStateException> { coordinator.handle(action) }
                assertTrue(coordinator.unregisterHandler(later))
                coordinator.registerHandler(added)
                calls.add("continued")
            }
        })
        coordinator.registerHandler(later)

        coordinator.handle(action)

        assertEquals(listOf("first", "continued", "later"), calls)
        calls.clear()
        coordinator.handle(action)
        assertEquals(listOf("first", "added"), calls)
    }

    @Test
    fun `fatal errors propagate after pending changes are flushed and dispatch can be reused`() =
        withRecordedFailures { failures ->
            val coordinator = HandlerCoordinator()
            val calls = mutableListOf<String>()
            val fatal = AssertionError("fatal handler failure")
            val added = handler(Action::class.java) { calls.add("added") }
            lateinit var broken: ActionHandler<Action>
            broken = handler(Action::class.java) {
                calls.add("broken")
                assertTrue(coordinator.unregisterHandler(broken))
                coordinator.registerHandler(added)
                throw fatal
            }
            coordinator.registerHandler(broken)
            coordinator.registerHandler(handler(Action::class.java) { calls.add("later") })

            assertSame(fatal, assertFailsWith<AssertionError> { coordinator.handle(action) })

            assertEquals(listOf("broken"), calls)
            assertTrue(failures.isEmpty())
            calls.clear()
            coordinator.handle(action)
            assertEquals(listOf("later", "added"), calls)
        }

    private fun <T : Action> handler(actionClass: Class<T>, callback: (T) -> Unit): ActionHandler<T> =
        object : ActionHandler<T> {
            override val actionClass = actionClass
            override fun handle(action: T) = callback(action)
        }

    private fun withRecordedFailures(test: (List<Throwable>) -> Unit) {
        val failures = mutableListOf<Throwable>()
        val logger = Logger.getLogger(HandlerCoordinator::class.java.name)
        val originalLevel = logger.level
        val originalUseParentHandlers = logger.useParentHandlers
        val recorder = object : Handler() {
            override fun publish(record: LogRecord) { record.thrown?.let(failures::add) }
            override fun flush() = Unit
            override fun close() = Unit
        }
        recorder.level = Level.ALL
        logger.level = Level.ALL
        logger.useParentHandlers = false
        logger.addHandler(recorder)
        try {
            test(failures)
        } finally {
            logger.removeHandler(recorder)
            logger.level = originalLevel
            logger.useParentHandlers = originalUseParentHandlers
        }
    }

    private class SpecialAction : Action(Key("test", "special"), UUID(0L, 0L), 1, 2, 3)
    private class OtherAction : Action(Key("test", "other"), UUID(0L, 0L), 1, 2, 3)

    private class EqualHandler(private val name: String, private val calls: MutableList<String>) : ActionHandler<Action> {
        override val actionClass = Action::class.java
        override fun handle(action: Action) { calls.add(name) }
        override fun equals(other: Any?) = other is EqualHandler
        override fun hashCode() = 0
    }
}
