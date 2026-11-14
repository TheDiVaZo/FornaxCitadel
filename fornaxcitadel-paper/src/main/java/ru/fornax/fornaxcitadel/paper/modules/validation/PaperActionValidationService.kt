package ru.fornax.fornaxcitadel.paper.modules.validation

import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService

/** Owns common platform validation independently of permissions and gameplay listeners. */
class PaperActionValidationService(
    private val actions: ActionManager,
    private val validator: PaperActionStateValidator
) : LifecycleService {
    override fun enable() {
        actions.validatorCoordinator.registerValidator(validator)
    }

    override fun disable() {
        actions.validatorCoordinator.unregisterValidator(validator)
    }
}
