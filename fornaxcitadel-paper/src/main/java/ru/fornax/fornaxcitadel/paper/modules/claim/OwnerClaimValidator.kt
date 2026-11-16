package ru.fornax.fornaxcitadel.paper.modules.claim

import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.claim.ClaimAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource

/** Grants claims of unowned targets only. Claiming never transfers existing ownership. */
class OwnerClaimValidator(private val citadels: CitadelFacade) : ActionValidator<ClaimAction> {
    override val actionClass = ClaimAction::class.java

    override fun validate(action: ClaimAction): ActionValidator.Result {
        if (action.context.source !is PlayerSource) return ActionValidator.Result.Rejected
        val citadel = try {
            citadels.findProtecting(action.position)
        } catch (_: CitadelResolutionUnavailableException) {
            return ActionValidator.Result.Rejected
        } ?: return ActionValidator.Result.Rejected
        return if (citadel.getComponent(CitadelOwner::class.java) == null) ActionValidator.Result.Success
        else ActionValidator.Result.Rejected
    }
}
