package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.claim.ClaimAction
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction

/** Checks core execution prerequisites without granting authorization or loading chunks. */
class CitadelActionValidator(
    private val citadels: CitadelFacade,
    private val definitions: CitadelDefinitionRegistry
) : ActionValidator<ActionData> {
    override val actionClass = ActionData::class.java

    override fun validate(action: ActionData): ActionValidator.Result {
        if (action !is PlaceAction && action !is DamageAction && action !is RemoveAction &&
            action !is DismantleAction && action !is RepairAction && action !is UpgradeAction && action !is ClaimAction) return ActionValidator.Result.Neutral
        if (!CitadelPosition.isSupported(action.citadelX, action.citadelY, action.citadelZ)) {
            return ActionValidator.Result.Rejected
        }
        return try {
            if (action is UpgradeAction && definitions.find(action.definitionKey) == null) {
                return ActionValidator.Result.Rejected
            }
            if (action is PlaceAction) {
                if (definitions.find(action.definitionKey) == null) return ActionValidator.Result.Rejected
                val responsible = citadels.getResponsiblePosition(action.position)
                if (citadels.findProtecting(action.position) != null ||
                    (responsible != action.position && citadels.findProtecting(responsible) != null)) {
                    return ActionValidator.Result.Rejected
                }
            } else if (citadels.findProtecting(action.position) == null) {
                return ActionValidator.Result.Rejected
            }
            ActionValidator.Result.Neutral
        } catch (_: CitadelResolutionUnavailableException) {
            ActionValidator.Result.Rejected
        }
    }
}
