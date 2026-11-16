package ru.fornax.fornaxcitadel.paper.modules.upgrade

import ru.fornax.fornaxcitadel.core.citadel.isOwnerOrUnowned
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource

/** Grants upgrade to the owner or an unowned target; other authorizers may grant access when this one abstains. */
class OwnerUpgradeValidator(private val citadels: CitadelFacade) : ActionValidator<UpgradeAction> {
    override val actionClass = UpgradeAction::class.java

    override fun validate(action: UpgradeAction): ActionValidator.Result {
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionValidator.Result.Neutral
        val citadel = try {
            citadels.findProtecting(action.position)
        } catch (_: CitadelResolutionUnavailableException) {
            return ActionValidator.Result.Rejected
        } ?: return ActionValidator.Result.Neutral
        return if (citadel.isOwnerOrUnowned(player.uniqueId)) ActionValidator.Result.Success
        else ActionValidator.Result.Neutral
    }
}
