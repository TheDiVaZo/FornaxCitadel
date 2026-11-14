package ru.fornax.fornaxcitadel.paper.modules.health

import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService

/** Owns damage authorization independently of gameplay listeners and general permissions. */
class PaperDamageValidationService(
    private val actions: ActionManager,
    private val validator: PaperDamageValidator
) : LifecycleService {
    override fun enable() {
        actions.validatorCoordinator.registerValidator(validator)
    }

    override fun disable() {
        actions.validatorCoordinator.unregisterValidator(validator)
    }
}
