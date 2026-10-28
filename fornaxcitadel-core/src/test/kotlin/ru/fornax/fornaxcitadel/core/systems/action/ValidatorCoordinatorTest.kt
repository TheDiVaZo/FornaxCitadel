package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator.Result
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ValidatorCoordinatorTest {
    private val action = Action(Key("test", "action"), UUID(0L, 0L), 1, 2, 3)

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
            coordinator.registerValidator(validator(Action::class.java) {
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
                coordinator.registerValidator(validator(Action::class.java) {
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
            coordinator.registerValidator(validator(Action::class.java) {
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
        coordinator.registerValidator(validator(Action::class.java) {
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
        val first = validator(Action::class.java) { firstCalls++; Result.Neutral }
        val second = validator(Action::class.java) { secondCalls++; Result.Success }
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
        coordinator.registerValidator(validator(Action::class.java) { throw failure })

        assertSame(failure, assertFailsWith<IllegalStateException> { coordinator.validate(action) })
    }

    private fun <T : Action> validator(
        actionClass: Class<T>,
        validate: (T) -> Result
    ): ActionValidator<T> = object : ActionValidator<T> {
        override val actionClass = actionClass
        override fun validate(action: T): Result = validate.invoke(action)
    }

    private class SpecialAction : Action(Key("test", "special"), UUID(0L, 0L), 1, 2, 3)
}
