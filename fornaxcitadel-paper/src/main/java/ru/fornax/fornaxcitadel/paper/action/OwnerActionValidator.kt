package ru.fornax.fornaxcitadel.paper.action

import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator

/** Rejects unowned targets; other authorizers may grant access to another player's reinforcement. */
internal class OwnerActionValidator<T : ActionData>(
    override val actionClass: Class<T>,
    private val citadels: CitadelFacade,
    private val unavailableResult: ActionValidator.Result = ActionValidator.Result.Neutral
) : ActionValidator<T> {
    override fun validate(action: T): ActionValidator.Result {
        val citadel = try {
            citadels.findProtecting(action.position)
        } catch (_: CitadelResolutionUnavailableException) {
            return unavailableResult
        } ?: return ActionValidator.Result.Neutral
        val owner = citadel.getComponent(CitadelOwner::class.java) ?: return ActionValidator.Result.Rejected
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionValidator.Result.Neutral
        return if (owner.playerId == player.uniqueId) ActionValidator.Result.Success
        else ActionValidator.Result.Neutral
    }
}
