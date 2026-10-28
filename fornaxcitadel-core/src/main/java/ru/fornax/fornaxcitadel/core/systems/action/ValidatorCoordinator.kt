package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator.Result

/**
 * Validates actions synchronously on the calling thread, in registration order.
 * Not thread-safe; use on the owning game thread, like [ProcessorCoordinator].
 */
class ValidatorCoordinator {
    private val validators = mutableListOf<ActionValidator<*>>()

    /** Multiple validators can share an action class. Registering the same instance is a no-op. */
    fun registerValidator(validator: ActionValidator<*>) {
        if (validators.none { it === validator }) {
            validators.add(validator)
        }
    }

    /**
     * Runs validators whose action class accepts [action], including validators for base classes.
     * A rejection stops validation immediately. Otherwise, any success yields [Result.Success];
     * if all validators abstain or none apply, returns [Result.Neutral].
     * Exceptions from validators propagate to the caller.
     */
    fun validate(action: Action): Result {
        var result: Result = Result.Neutral
        for (validator in validators) {
            when (validateAction(validator, action)) {
                Result.Success -> result = Result.Success
                Result.Neutral -> Unit
                Result.Rejected -> return Result.Rejected
            }
        }
        return result
    }

    private fun <T : Action> validateAction(validator: ActionValidator<T>, action: Action): Result {
        val actionClass = validator.actionClass
        if (!actionClass.isInstance(action)) return Result.Neutral
        return validator.validate(actionClass.cast(action))
    }
}
