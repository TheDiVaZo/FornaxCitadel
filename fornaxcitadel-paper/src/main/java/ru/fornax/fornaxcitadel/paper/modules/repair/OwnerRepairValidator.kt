package ru.fornax.fornaxcitadel.paper.modules.repair

import ru.fornax.fornaxcitadel.core.citadel.isOwnerOrUnowned
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource

/** Grants repair to the owner or an unowned target; other authorizers may grant access when this one abstains. */
class OwnerRepairValidator(private val citadels: CitadelFacade) : ActionValidator<RepairAction> {
    override val actionClass = RepairAction::class.java

    override fun validate(action: RepairAction): ActionValidator.Result {
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionValidator.Result.Neutral
        val citadel = try {
            citadels.findProtecting(action.position)
        } catch (_: CitadelResolutionUnavailableException) {
            return ActionValidator.Result.Neutral
        } ?: return ActionValidator.Result.Neutral
        return if (citadel.isOwnerOrUnowned(player.uniqueId)) ActionValidator.Result.Success
        else ActionValidator.Result.Neutral
    }
}
