package ru.fornax.fornaxcitadel.paper.action

import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key

/** Owns the processor and validator registrations for one gameplay service. */
internal class ActionRegistration<T : ActionData>(
    private val actions: ActionManager,
    type: Class<T>,
    key: Key,
    validators: List<ActionValidator<T>>,
    process: (T) -> ActionResult
) : LifecycleService {
    private val validators = validators.toList()
    private val processor = object : ActionProcessor<T>() {
        override val actionClass = type
        override val actionKey = key
        override fun execute(action: T) = process(action)
    }
    var isEnabled = false
        private set

    override fun enable() {
        if (isEnabled) return
        actions.processorCoordinator.registerProcessor(processor)
        try {
            validators.forEach(actions.validatorCoordinator::registerValidator)
            isEnabled = true
        } catch (failure: Throwable) {
            validators.asReversed().forEach(actions.validatorCoordinator::unregisterValidator)
            actions.processorCoordinator.unregisterProcessor(processor)
            throw failure
        }
    }

    override fun disable() {
        validators.asReversed().forEach(actions.validatorCoordinator::unregisterValidator)
        actions.processorCoordinator.unregisterProcessor(processor)
        isEnabled = false
    }
}
