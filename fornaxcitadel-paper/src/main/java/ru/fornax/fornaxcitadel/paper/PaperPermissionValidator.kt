package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Server
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.breaking.BreakAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionDamageAction

/** Platform permissions are checked for every request, including commands and posted actions. */
class PaperPermissionValidator(
    private val server: Server,
    private val citadelTypes: PaperCitadelTypes
) : ActionValidator<ActionData> {
    override val actionClass = ActionData::class.java

    fun canAdminister(context: ActionContext): Boolean {
        val player = context.actorId?.let(server::getPlayer) ?: return false
        return player.isOnline && player.hasPermission("fornaxcitadel.admin")
    }

    override fun validate(action: ActionData): ActionValidator.Result {
        val permission = when (action) {
            // Environmental eligibility is typed; context.source is attribution, not permission.
            is ExplosionDamageAction -> null
            is PlaceAction -> "fornaxcitadel.place"
            is BreakAction, is DamageAction -> "fornaxcitadel.damage"
            is RemoveAction -> "fornaxcitadel.admin"
            else -> return ActionValidator.Result.Neutral
        }
        if (permission != null) {
            val player = action.context.actorId?.let(server::getPlayer) ?: return ActionValidator.Result.Rejected
            if (!player.isOnline || player.gameMode == GameMode.SPECTATOR || !player.hasPermission(permission)) {
                return ActionValidator.Result.Rejected
            }
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
        return ActionValidator.Result.Success
    }
}
