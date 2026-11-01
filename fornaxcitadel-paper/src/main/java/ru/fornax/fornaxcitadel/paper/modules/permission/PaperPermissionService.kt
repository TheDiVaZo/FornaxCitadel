package ru.fornax.fornaxcitadel.paper.modules.permission

import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService

/** Owns the action subscription; the same validator can also supply the core removal policy. */
class PaperPermissionService(
    private val actions: ActionManager,
    private val validator: PaperPermissionValidator
) : LifecycleService {
    override fun enable() {
        actions.validatorCoordinator.registerValidator(validator)
    }

    override fun disable() {
        actions.validatorCoordinator.unregisterValidator(validator)
    }
}
