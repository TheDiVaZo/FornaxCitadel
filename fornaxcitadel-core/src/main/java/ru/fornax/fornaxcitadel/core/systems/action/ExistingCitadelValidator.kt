package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.ReadableCitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException

/** Requires an available target without granting authorization or loading chunks. */
class ExistingCitadelValidator<T : ActionData>(
    override val actionClass: Class<T>,
    private val citadels: ReadableCitadelFacade
) : ActionValidator<T> {
    override fun validate(action: T): ActionValidator.Result {
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
