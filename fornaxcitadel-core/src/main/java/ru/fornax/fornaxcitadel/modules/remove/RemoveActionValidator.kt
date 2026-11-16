package ru.fornax.fornaxcitadel.modules.remove

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator

/** Checks removal prerequisites without granting authorization or loading chunks. */
class RemoveActionValidator(private val citadels: CitadelFacade) : ActionValidator<RemoveAction> {
    override val actionClass = RemoveAction::class.java

    override fun validate(action: RemoveAction): ActionValidator.Result {
        if (!CitadelPosition.isSupported(action.citadelX, action.citadelY, action.citadelZ)) {
            return ActionValidator.Result.Rejected
        }
        return try {
            if (citadels.findProtecting(action.position) == null) ActionValidator.Result.Rejected
            else ActionValidator.Result.Neutral
        } catch (_: CitadelResolutionUnavailableException) {
            ActionValidator.Result.Rejected
        }
    }
}
