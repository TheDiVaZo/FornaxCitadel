package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService

/** Independently owns one validator without coupling its lifetime to a processor or listener. */
class ValidatorRegistration(
    private val actions: ActionManager,
    private val validator: ActionValidator<*>
) : LifecycleService {
    override fun enable() {
        actions.validatorCoordinator.registerValidator(validator)
    }

    override fun disable() {
        actions.validatorCoordinator.unregisterValidator(validator)
    }
}
