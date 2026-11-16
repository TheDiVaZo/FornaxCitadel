package ru.fornax.fornaxcitadel.paper.modules.dismantle

import ru.fornax.fornaxcitadel.core.citadel.isOwnerOrUnowned
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource

/** Grants dismantling to the owner or an unowned target; other authorizers may grant access when this one abstains. */
class OwnerDismantleValidator(private val citadels: CitadelFacade) : ActionValidator<DismantleAction> {
    override val actionClass = DismantleAction::class.java

    override fun validate(action: DismantleAction): ActionValidator.Result {
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
