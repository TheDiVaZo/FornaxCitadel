package ru.fornax.fornaxcitadel.paper.modules.permission

import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.claim.ClaimAction
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes

/** Checks player permission nodes; other sources are left to other authorizers. */
class PaperPermissionValidator(
    private val citadelTypes: PaperCitadelTypes
) : ActionValidator<ActionData> {
    override val actionClass = ActionData::class.java

    fun canAdminister(context: ActionContext): Boolean {
        val player = (context.source as? PlayerSource)?.player ?: return false
        return player.hasPermission("fornaxcitadel.admin")
    }

    override fun validate(action: ActionData): ActionValidator.Result {
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionValidator.Result.Neutral
        val permission = when (action) {
            is PlaceAction -> "fornaxcitadel.place"
            is DamageAction -> "fornaxcitadel.damage"
            is DismantleAction -> "fornaxcitadel.dismantle"
            is RepairAction -> "fornaxcitadel.repair"
            is ClaimAction -> "fornaxcitadel.repair"
            is UpgradeAction -> "fornaxcitadel.repair"
            is RemoveAction -> "fornaxcitadel.admin"
            else -> return ActionValidator.Result.Neutral
        }
        if (!player.hasPermission(permission)) {
            return ActionValidator.Result.Rejected
        }
        if (action is UpgradeAction && !player.hasPermission("fornaxcitadel.place")) {
            return ActionValidator.Result.Rejected
        }
        val destination = when (action) {
            is PlaceAction -> action.definitionKey
            is UpgradeAction -> action.definitionKey
            else -> null
        }
        if (destination != null && citadelTypes.requiresAdmin(destination) && !canAdminister(action.context)) {
            return ActionValidator.Result.Rejected
        }
        return if (action is DamageAction || action is DismantleAction || action is RepairAction || action is UpgradeAction || action is ClaimAction) ActionValidator.Result.Neutral
        else ActionValidator.Result.Success
    }
}
