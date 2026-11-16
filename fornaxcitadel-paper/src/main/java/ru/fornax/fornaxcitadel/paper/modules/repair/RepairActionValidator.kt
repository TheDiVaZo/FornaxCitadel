package ru.fornax.fornaxcitadel.paper.modules.repair

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.ReadableCitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.repair.RepairAction

/** Checks repair prerequisites without granting authorization or loading chunks. */
class RepairActionValidator(private val citadels: ReadableCitadelFacade) : ActionValidator<RepairAction> {
    override val actionClass = RepairAction::class.java

    override fun validate(action: RepairAction): ActionValidator.Result {
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
