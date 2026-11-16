package ru.fornax.fornaxcitadel.paper.action

import ru.fornax.fornaxcitadel.core.citadel.isOwnerOrUnowned
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator

/** Grants access to owners and unowned targets; other authorizers may grant access to other players. */
internal class OwnerActionValidator<T : ActionData>(
    override val actionClass: Class<T>,
    private val citadels: CitadelFacade,
    private val unavailableResult: ActionValidator.Result = ActionValidator.Result.Neutral
) : ActionValidator<T> {
    override fun validate(action: T): ActionValidator.Result {
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionValidator.Result.Neutral
        val citadel = try {
            citadels.findProtecting(action.position)
        } catch (_: CitadelResolutionUnavailableException) {
            return unavailableResult
        } ?: return ActionValidator.Result.Neutral
        return if (citadel.isOwnerOrUnowned(player.uniqueId)) ActionValidator.Result.Success
        else ActionValidator.Result.Neutral
    }
}
