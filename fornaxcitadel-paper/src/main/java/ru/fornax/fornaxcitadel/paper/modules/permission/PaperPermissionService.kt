package ru.fornax.fornaxcitadel.paper.modules.permission

import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService

/** Owns the platform permission validator's action subscription. */
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
