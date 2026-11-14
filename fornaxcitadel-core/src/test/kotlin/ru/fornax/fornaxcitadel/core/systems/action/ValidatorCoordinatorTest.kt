package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator.Result
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ValidatorCoordinatorTest {
    private val action = ActionData(Key("test", "action"), UUID(0L, 0L), 1, 2, 3)

    @Test
    fun `no compatible validators produces neutral without invoking unrelated validators`() {
        val coordinator = ValidatorCoordinator()
        assertSame(Result.Neutral, coordinator.validate(action))

        coordinator.registerValidator(validator(SpecialAction::class.java) {
            error("An unrelated action must not reach this validator")
        })

        assertSame(Result.Neutral, coordinator.validate(action))
    }

    @Test
    fun `all neutral validators produce neutral`() {
        val coordinator = ValidatorCoordinator()
        var calls = 0
        repeat(2) {
            coordinator.registerValidator(validator(ActionData::class.java) {
                calls++
                Result.Neutral
            })
        }

        assertSame(Result.Neutral, coordinator.validate(action))
        assertEquals(2, calls)
    }

    @Test
    fun `success wins over neutral in either registration order`() {
        val orders = listOf(
            listOf(Result.Success, Result.Neutral),
            listOf(Result.Neutral, Result.Success)
        )
        for (results in orders) {
            val coordinator = ValidatorCoordinator()
            val visited = mutableListOf<Result>()
            for (result in results) {
                coordinator.registerValidator(validator(ActionData::class.java) {
                    visited.add(result)
                    result
                })
            }

            assertSame(Result.Success, coordinator.validate(action))
            assertEquals(results, visited)
        }
    }

    @Test
    fun `rejection overrides success and stops remaining validators`() {
        val coordinator = ValidatorCoordinator()
        val visited = mutableListOf<Result>()
        for (result in listOf(Result.Success, Result.Rejected, Result.Success)) {
            coordinator.registerValidator(validator(ActionData::class.java) {
                visited.add(result)
                result
            })
        }

        assertSame(Result.Rejected, coordinator.validate(action))
        assertEquals(listOf(Result.Success, Result.Rejected), visited)
    }

    @Test
    fun `base class and specific validators receive the original subtype instance`() {
        val coordinator = ValidatorCoordinator()
        val special = SpecialAction()
        val visited = mutableListOf<String>()
        coordinator.registerValidator(validator(ActionData::class.java) {
            assertSame(special, it)
            visited.add("base")
            Result.Neutral
        })
        coordinator.registerValidator(validator(SpecialAction::class.java) {
            assertSame(special, it)
            visited.add("specific")
            Result.Success
        })

        assertSame(Result.Success, coordinator.validate(special))
        assertEquals(listOf("base", "specific"), visited)
    }

    @Test
    fun `repeated registration is idempotent while distinct validators for one class remain active`() {
        val coordinator = ValidatorCoordinator()
        var firstCalls = 0
        var secondCalls = 0
        val first = validator(ActionData::class.java) { firstCalls++; Result.Neutral }
        val second = validator(ActionData::class.java) { secondCalls++; Result.Success }
        coordinator.registerValidator(first)
        coordinator.registerValidator(second)
        coordinator.registerValidator(first)

        assertSame(Result.Success, coordinator.validate(action))
        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun `validator exceptions propagate unchanged`() {
        val coordinator = ValidatorCoordinator()
        val failure = IllegalStateException("Validation failed unexpectedly")
        coordinator.registerValidator(validator(ActionData::class.java) { throw failure })

        assertSame(failure, assertFailsWith<IllegalStateException> { coordinator.validate(action) })
    }

    @Test
    fun `unregistration uses identity even when different validators compare equal`() {
        val coordinator = ValidatorCoordinator()
        val registered = EqualValidator()
        val other = EqualValidator()
        coordinator.registerValidator(registered)

        assertEquals(registered, other)
        assertFalse(coordinator.unregisterValidator(other))
        assertSame(Result.Rejected, coordinator.validate(action))
        assertTrue(coordinator.unregisterValidator(registered))
        assertFalse(coordinator.unregisterValidator(registered))
        assertSame(Result.Neutral, coordinator.validate(action))
    }

    @Test
    fun `registration changes in a callback affect the next action`() {
        val coordinator = ValidatorCoordinator()
        val calls = mutableListOf<String>()
        val later = validator(ActionData::class.java) { calls.add("later"); Result.Success }
        val added = validator(ActionData::class.java) { calls.add("added"); Result.Rejected }
        lateinit var first: ActionValidator<ActionData>
        first = validator(ActionData::class.java) {
            calls.add("first")
            coordinator.unregisterValidator(first)
            coordinator.unregisterValidator(later)
            coordinator.registerValidator(added)
            Result.Neutral
        }
        coordinator.registerValidator(first)
        coordinator.registerValidator(later)

        assertSame(Result.Success, coordinator.validate(action))
        assertEquals(listOf("first", "later"), calls)
        calls.clear()
        assertSame(Result.Rejected, coordinator.validate(action))
        assertEquals(listOf("added"), calls)
    }

    @Test
    fun `pending changes use effective membership and preserve FIFO order for the next validation`() {
        val coordinator = ValidatorCoordinator()
        val calls = mutableListOf<String>()
        val moved = validator(ActionData::class.java) { calls.add("moved"); Result.Neutral }
        val tail = validator(ActionData::class.java) { calls.add("tail"); Result.Neutral }
        val added = validator(ActionData::class.java) { calls.add("added"); Result.Success }
        val temporary = validator(ActionData::class.java) { error("An added then removed validator must stay absent") }
        lateinit var first: ActionValidator<ActionData>
        first = validator(ActionData::class.java) {
            calls.add("first")
            assertTrue(coordinator.unregisterValidator(first))
            assertFalse(coordinator.unregisterValidator(first))
            coordinator.registerValidator(tail)
            assertTrue(coordinator.unregisterValidator(moved))
            assertFalse(coordinator.unregisterValidator(moved))
            coordinator.registerValidator(moved)
            coordinator.registerValidator(moved)
            coordinator.registerValidator(added)
            coordinator.registerValidator(added)
            coordinator.registerValidator(temporary)
            coordinator.registerValidator(temporary)
            assertTrue(coordinator.unregisterValidator(temporary))
            assertFalse(coordinator.unregisterValidator(temporary))
            Result.Neutral
        }
        coordinator.registerValidator(first)
        coordinator.registerValidator(moved)
        coordinator.registerValidator(tail)

        assertSame(Result.Neutral, coordinator.validate(action))
        assertEquals(listOf("first", "moved", "tail"), calls)
        calls.clear()
        assertSame(Result.Success, coordinator.validate(action))
        assertEquals(listOf("tail", "moved", "added"), calls)
        assertFalse(coordinator.unregisterValidator(temporary))
    }

    @Test
    fun `pending registrations and removals use identity for validators that compare equal`() {
        val coordinator = ValidatorCoordinator()
        val registered = EqualValidator()
        val other = EqualValidator()
        lateinit var first: ActionValidator<ActionData>
        first = validator(ActionData::class.java) {
            assertTrue(coordinator.unregisterValidator(first))
            assertFalse(coordinator.unregisterValidator(other))
            coordinator.registerValidator(other)
            coordinator.registerValidator(other)
            assertTrue(coordinator.unregisterValidator(registered))
            assertFalse(coordinator.unregisterValidator(registered))
            assertTrue(coordinator.unregisterValidator(other))
            assertFalse(coordinator.unregisterValidator(other))
            Result.Neutral
        }
        coordinator.registerValidator(first)
        coordinator.registerValidator(registered)

        assertSame(Result.Rejected, coordinator.validate(action))
        assertSame(Result.Neutral, coordinator.validate(action))
    }

    @Test
    fun `early rejection and callback exceptions still flush pending registrations`() {
        for (failure in listOf(null, IllegalArgumentException("Validation failed"))) {
            val coordinator = ValidatorCoordinator()
            val calls = mutableListOf<String>()
            val later = validator(ActionData::class.java) { calls.add("later"); Result.Neutral }
            val added = validator(ActionData::class.java) { calls.add("added"); Result.Success }
            lateinit var first: ActionValidator<ActionData>
            first = validator(ActionData::class.java) {
                calls.add("first")
                assertTrue(coordinator.unregisterValidator(first))
                assertTrue(coordinator.unregisterValidator(later))
                coordinator.registerValidator(added)
                if (failure != null) throw failure
                Result.Rejected
            }
            coordinator.registerValidator(first)
            coordinator.registerValidator(later)

            if (failure == null) assertSame(Result.Rejected, coordinator.validate(action))
            else assertSame(failure, assertFailsWith<IllegalArgumentException> { coordinator.validate(action) })
            assertEquals(listOf("first"), calls)
            calls.clear()
            assertSame(Result.Success, coordinator.validate(action))
            assertEquals(listOf("added"), calls)
        }
    }

    @Test
    fun `caught nested validation leaves the outer traversal and pending changes intact`() {
        val coordinator = ValidatorCoordinator()
        val calls = mutableListOf<String>()
        val later = validator(ActionData::class.java) { calls.add("later"); Result.Neutral }
        val added = validator(ActionData::class.java) { calls.add("added"); Result.Success }
        val last = validator(ActionData::class.java) { calls.add("last"); Result.Neutral }
        lateinit var first: ActionValidator<ActionData>
        first = validator(ActionData::class.java) {
            calls.add("first")
            assertTrue(coordinator.unregisterValidator(first))
            assertTrue(coordinator.unregisterValidator(later))
            coordinator.registerValidator(added)
            assertFailsWith<IllegalStateException> { coordinator.validate(action) }
            assertFalse(coordinator.unregisterValidator(later))
            coordinator.registerValidator(last)
            assertFailsWith<IllegalStateException> { coordinator.validate(action) }
            Result.Neutral
        }
        coordinator.registerValidator(first)
        coordinator.registerValidator(later)

        assertSame(Result.Neutral, coordinator.validate(action))
        assertEquals(listOf("first", "later"), calls)
        calls.clear()
        assertSame(Result.Success, coordinator.validate(action))
        assertEquals(listOf("added", "last"), calls)
    }

    @Test
    fun `uncaught nested validation flushes outer changes and releases the traversal guard`() {
        val coordinator = ValidatorCoordinator()
        var replacementCalls = 0
        val replacement = validator(ActionData::class.java) { replacementCalls++; Result.Success }
        lateinit var first: ActionValidator<ActionData>
        first = validator(ActionData::class.java) {
            assertTrue(coordinator.unregisterValidator(first))
            coordinator.registerValidator(replacement)
            coordinator.validate(action)
        }
        coordinator.registerValidator(first)

        assertFailsWith<IllegalStateException> { coordinator.validate(action) }
        assertEquals(0, replacementCalls)
        assertSame(Result.Success, coordinator.validate(action))
        assertEquals(1, replacementCalls)
    }

    private fun <T : ActionData> validator(
        actionClass: Class<T>,
        validate: (T) -> Result
    ): ActionValidator<T> = object : ActionValidator<T> {
        override val actionClass = actionClass
        override fun validate(action: T): Result = validate.invoke(action)
    }

    private class SpecialAction : ActionData(Key("test", "special"), UUID(0L, 0L), 1, 2, 3)

    private class EqualValidator : ActionValidator<ActionData> {
        override val actionClass = ActionData::class.java
        override fun validate(action: ActionData) = Result.Rejected
        override fun equals(other: Any?) = other is EqualValidator
        override fun hashCode() = 0
    }
}
