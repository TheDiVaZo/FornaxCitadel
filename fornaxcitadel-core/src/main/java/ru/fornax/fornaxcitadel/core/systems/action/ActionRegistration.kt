package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.util.Key

/** Owns the processor and validator registrations for one gameplay service. */
class ActionRegistration<T : ActionData>(
    private val actions: ActionManager,
    private val processor: ActionProcessor<T>,
    validators: List<ActionValidator<*>>
) : LifecycleService {
    private val validators = validators.toList()

    constructor(
        actions: ActionManager,
        type: Class<T>,
        key: Key,
        validators: List<ActionValidator<*>>,
        process: (T) -> ActionResult
    ) : this(actions, object : ActionProcessor<T>() {
        override val actionClass = type
        override val actionKey = key
        override fun execute(action: T) = process(action)
    }, validators)

    var isEnabled = false
        private set

    override fun enable() {
        actions.registerProcessor(processor)
        try {
            validators.forEach(actions::registerValidator)
            isEnabled = true
        } catch (failure: Throwable) {
            validators.asReversed().forEach(actions::unregisterValidator)
            actions.unregisterProcessor(processor)
            throw failure
        }
    }

    override fun disable() {
        validators.asReversed().forEach(actions::unregisterValidator)
        actions.unregisterProcessor(processor)
        isEnabled = false
    }
}
