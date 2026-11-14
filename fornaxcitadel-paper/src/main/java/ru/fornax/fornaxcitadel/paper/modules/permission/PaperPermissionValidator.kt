package ru.fornax.fornaxcitadel.paper.modules.permission

import org.bukkit.GameMode
import org.bukkit.Server
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes

/** Checks captured players when requests execute; other sources are left to other validators. */
class PaperPermissionValidator(
    private val server: Server,
    private val citadelTypes: PaperCitadelTypes
) : ActionValidator<ActionData> {
    override val actionClass = ActionData::class.java

    fun canAdminister(context: ActionContext): Boolean {
        val player = (context.source as? PlayerSource)?.player ?: return false
        return player.isOnline && player.hasPermission("fornaxcitadel.admin")
    }

    override fun validate(action: ActionData): ActionValidator.Result {
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionValidator.Result.Neutral
        val permission = when (action) {
            is PlaceAction -> "fornaxcitadel.place"
            is DamageAction -> "fornaxcitadel.damage"
            is RemoveAction -> "fornaxcitadel.admin"
            else -> return ActionValidator.Result.Neutral
        }
        if (!player.isOnline || player.gameMode == GameMode.SPECTATOR || !player.hasPermission(permission)) {
            return ActionValidator.Result.Rejected
        }
        if (!CitadelPosition.isSupported(action.citadelX, action.citadelY, action.citadelZ)) {
            return ActionValidator.Result.Rejected
        }
        val world = server.getWorld(action.citadelW) ?: return ActionValidator.Result.Rejected
        if (!world.isChunkLoaded(action.citadelX shr 4, action.citadelZ shr 4)) {
            return ActionValidator.Result.Rejected
        }
        if (action is PlaceAction) {
            if (citadelTypes.requiresAdmin(action.definitionKey) && !canAdminister(action.context)) {
                return ActionValidator.Result.Rejected
            }
        }
        return if (action is DamageAction) ActionValidator.Result.Neutral else ActionValidator.Result.Success
    }
}
