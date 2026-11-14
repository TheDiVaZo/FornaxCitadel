package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator.Result
import ru.fornax.fornaxcitadel.core.util.DeferredRegistrationList

/**
 * Validates actions synchronously on the calling thread, in registration order.
 * Not thread-safe; use on the owning game thread, like [ProcessorCoordinator].
 */
class ValidatorCoordinator {
    private val validators = DeferredRegistrationList<ActionValidator<*>>()

    /** Multiple validators can share an action class. Registering the same instance is a no-op. */
    fun registerValidator(validator: ActionValidator<*>) {
        validators.register(validator)
    }

    /** Removes this instance, accounting for registration changes already queued by callbacks. */
    fun unregisterValidator(validator: ActionValidator<*>): Boolean = validators.unregister(validator)

    /**
     * Runs validators whose action class accepts [action], including validators for base classes.
     * A rejection stops validation immediately. Otherwise, any success yields [Result.Success];
     * if all validators abstain or none apply, returns [Result.Neutral].
     * Exceptions from validators propagate to the caller. Registration changes during callbacks
     * are applied in order when validation finishes, including after rejection or an exception.
     * The active list remains unchanged during traversal. Nested validation on this coordinator is rejected.
     */
    fun validate(action: ActionData): Result {
        val current = validators.beginIteration()
        try {
            var result: Result = Result.Neutral
            var index = 0
            while (index < current.size) {
                when (validateAction(current[index++], action)) {
                    Result.Success -> result = Result.Success
                    Result.Neutral -> Unit
                    Result.Rejected -> return Result.Rejected
                }
            }
            return result
        } finally {
            validators.endIteration()
        }
    }

    private fun <T : ActionData> validateAction(validator: ActionValidator<T>, action: ActionData): Result {
        val actionClass = validator.actionClass
        if (!actionClass.isInstance(action)) return Result.Neutral
        return validator.validate(actionClass.cast(action))
    }
}
