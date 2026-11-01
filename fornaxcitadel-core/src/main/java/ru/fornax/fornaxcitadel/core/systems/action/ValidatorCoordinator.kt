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

    /** Removes only the supplied instance, leaving other validators for the same action intact. */
    fun unregisterValidator(validator: ActionValidator<*>): Boolean {
        val index = validators.indexOfFirst { it === validator }
        if (index < 0) return false
        validators.removeAt(index)
        return true
    }

    /**
     * Runs validators whose action class accepts [action], including validators for base classes.
     * A rejection stops validation immediately. Otherwise, any success yields [Result.Success];
     * if all validators abstain or none apply, returns [Result.Neutral].
     * Exceptions from validators propagate to the caller. Registration changes during a callback
     * apply to the next validation; the current action uses a stable snapshot.
     */
    fun validate(action: ActionData): Result {
        var result: Result = Result.Neutral
        for (validator in validators.toList()) {
            when (validateAction(validator, action)) {
                Result.Success -> result = Result.Success
                Result.Neutral -> Unit
                Result.Rejected -> return Result.Rejected
            }
        }
        return result
    }

    private fun <T : ActionData> validateAction(validator: ActionValidator<T>, action: ActionData): Result {
        val actionClass = validator.actionClass
        if (!actionClass.isInstance(action)) return Result.Neutral
        return validator.validate(actionClass.cast(action))
    }
}
