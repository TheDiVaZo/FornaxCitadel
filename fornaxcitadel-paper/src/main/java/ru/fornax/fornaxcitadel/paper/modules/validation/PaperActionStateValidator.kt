package ru.fornax.fornaxcitadel.paper.modules.validation

import org.bukkit.GameMode
import org.bukkit.Server
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource

/** Checks platform prerequisites without granting authorization or loading chunks. */
class PaperActionStateValidator(private val server: Server) : ActionValidator<Action> {
    override val actionClass = Action::class.java

    override fun validate(action: Action): ActionValidator.Result {
        if (action !is PlaceAction && action !is DamageAction && action !is RemoveAction &&
            action !is DismantleAction && action !is RepairAction && action !is UpgradeAction) {
            return ActionValidator.Result.Neutral
        }
        if (action is UpgradeAction && !server.isPrimaryThread) return ActionValidator.Result.Rejected
        val player = (action.context.source as? PlayerSource)?.player
        if (player != null && (!player.isOnline || player.gameMode == GameMode.SPECTATOR)) {
            return ActionValidator.Result.Rejected
        }
        val position = action.position
        val requiresPlayerBlock = action is DismantleAction || action is RepairAction || action is UpgradeAction
        if (requiresPlayerBlock && (player == null || player.world.uid != position.world)) {
            return ActionValidator.Result.Rejected
        }
        if (!CitadelPosition.isSupported(position.x, position.y, position.z)) {
            return ActionValidator.Result.Rejected
        }
        val world = server.getWorld(position.world) ?: return ActionValidator.Result.Rejected
        if (position.y !in world.minHeight until world.maxHeight ||
            !world.isChunkLoaded(position.x shr 4, position.z shr 4)) {
            return ActionValidator.Result.Rejected
        }
        if (requiresPlayerBlock && world.getBlockAt(position.x, position.y, position.z).isEmpty) {
            return ActionValidator.Result.Rejected
        }
        return ActionValidator.Result.Neutral
    }
}
