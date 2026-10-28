package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
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
    fun `successful and neutral validation happen before processing the original action`() {
        for (result in listOf(Result.Success, Result.Neutral)) {
            val fixture = Fixture()
            val calls = mutableListOf<String>()
            fixture.registerValidator {
                assertSame(fixture.action, it)
                calls.add("validate")
                result
            }
            fixture.registerProcessor {
                assertSame(fixture.action, it)
                calls.add("execute")
                true
            }

            assertTrue(fixture.manager.execute(fixture.action))
            assertEquals(listOf("validate", "execute"), calls)
        }
    }

    @Test
    fun `rejection prevents processor side effects even after successful validation`() {
        val fixture = Fixture()
        fixture.registerValidator { Result.Success }
        fixture.registerValidator { Result.Rejected }
        fixture.registerProcessor { error("Rejected actions must not execute") }

        assertFalse(fixture.manager.execute(fixture.action))
    }

    @Test
    fun `without validators execution returns the processor result unchanged`() {
        for (result in listOf(true, false)) {
            val fixture = Fixture()
            var calls = 0
            fixture.registerProcessor {
                assertSame(fixture.action, it)
                calls++
                result
            }

            assertEquals(result, fixture.manager.execute(fixture.action))
            assertEquals(1, calls)
        }
    }

    @Test
    fun `missing processor returns false even when validation succeeds`() {
        val fixture = Fixture()
        fixture.registerValidator { Result.Success }

        assertFalse(fixture.manager.execute(fixture.action))
    }

    @Test
    fun `validation exceptions propagate without invoking the processor`() {
        val fixture = Fixture()
        val failure = IllegalStateException("Validation interrupted")
        fixture.registerValidator { throw failure }
        fixture.registerProcessor { error("Validation did not finish") }

        assertSame(failure, assertFailsWith<IllegalStateException> {
            fixture.manager.execute(fixture.action)
        })
    }

    private class Fixture {
        val action = Action(Key("test", "action"), UUID(0L, 0L), 1, 2, 3)
        val manager = ActionManager(SimpleComponentManager(UnusedStorage, TypeManager()))

        fun registerValidator(validate: (Action) -> Result) {
            manager.validatorCoordinator.registerValidator(object : ActionValidator<Action> {
                override val actionClass = Action::class.java
                override fun validate(action: Action): Result = validate.invoke(action)
            })
        }

        fun registerProcessor(execute: (Action) -> Boolean) {
            manager.processorCoordinator.registerProcessor(object : ActionProcessor<Action>(manager.componentManager) {
                override val actionClass = Action::class.java
                override val actionKey = action.type
                override fun execute(action: Action): Boolean = execute.invoke(action)
            })
        }
    }

    private object UnusedStorage : CitadelStorage {
        override fun getIfChunkLoaded(location: WXZCord): Nothing = error("Storage must not be used")
        override fun loadChunk(location: WXZCord): Nothing = error("Storage must not be used")
        override fun unloadChunk(location: WXZCord): Nothing = error("Storage must not be used")
        override fun updateOpaque(chunk: ChunkCord): Nothing = error("Storage must not be used")
        override fun updateOpaque(key: Key): Nothing = error("Storage must not be used")
        override fun saveAll(): Nothing = error("Storage must not be used")
        override fun closeAndSaveAll(): Nothing = error("Storage must not be used")
    }
}
