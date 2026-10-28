package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.ProcessorCoordinator
import ru.fornax.fornaxcitadel.core.systems.action.ValidatorCoordinator

/**
 * Validates and executes actions synchronously on the calling thread.
 * Not thread-safe; use on the owning game thread, like [componentManager].
 */
class ActionManager(
    val componentManager: SimpleComponentManager,
    val processorCoordinator: ProcessorCoordinator = ProcessorCoordinator(),
    val validatorCoordinator: ValidatorCoordinator = ValidatorCoordinator()
) {
    /**
     * Returns false without executing the processor if validation rejects the action.
     * Successful or neutral validation allows execution, returning the processor's result
     * (or false if no processor is registered). Exceptions propagate to the caller.
     */
    fun execute(action: Action): Boolean {
        return when (validatorCoordinator.validate(action)) {
            ActionValidator.Result.Rejected -> false
            ActionValidator.Result.Success, ActionValidator.Result.Neutral -> processorCoordinator.execute(action)
        }
    }
}
