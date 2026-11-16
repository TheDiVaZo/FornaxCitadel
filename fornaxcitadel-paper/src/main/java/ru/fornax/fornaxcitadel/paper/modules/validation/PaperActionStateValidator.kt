package ru.fornax.fornaxcitadel.paper.modules.validation

import org.bukkit.GameMode
import org.bukkit.Server
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.claim.ClaimAction
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource

/** Checks platform prerequisites without granting authorization or loading chunks. */
class PaperActionStateValidator(private val server: Server) : ActionValidator<ActionData> {
    override val actionClass = ActionData::class.java

    override fun validate(action: ActionData): ActionValidator.Result {
        if (action !is PlaceAction && action !is DamageAction && action !is RemoveAction &&
            action !is DismantleAction && action !is RepairAction && action !is ClaimAction) {
            return ActionValidator.Result.Neutral
        }
        if ((action is ClaimAction) && !server.isPrimaryThread) return ActionValidator.Result.Rejected
        val player = (action.context.source as? PlayerSource)?.player
        if (player != null && (!player.isOnline || player.gameMode == GameMode.SPECTATOR)) {
            return ActionValidator.Result.Rejected
        }
        val requiresPlayerBlock = action is DismantleAction || action is RepairAction || action is ClaimAction
        if (requiresPlayerBlock && (player == null || player.world.uid != action.citadelW)) {
            return ActionValidator.Result.Rejected
        }
        if (!CitadelPosition.isSupported(action.citadelX, action.citadelY, action.citadelZ)) {
            return ActionValidator.Result.Rejected
        }
        val world = server.getWorld(action.citadelW) ?: return ActionValidator.Result.Rejected
        if (action.citadelY !in world.minHeight until world.maxHeight ||
            !world.isChunkLoaded(action.citadelX shr 4, action.citadelZ shr 4)) {
            return ActionValidator.Result.Rejected
        }
        if (requiresPlayerBlock && world.getBlockAt(action.citadelX, action.citadelY, action.citadelZ).isEmpty) {
            return ActionValidator.Result.Rejected
        }
        return ActionValidator.Result.Neutral
    }
}
