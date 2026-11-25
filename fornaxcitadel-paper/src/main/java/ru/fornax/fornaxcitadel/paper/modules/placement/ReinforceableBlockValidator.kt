package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.Material
import org.bukkit.Server
import ru.fornax.fornaxcitadel.core.facade.ReadableCitadelFacade
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction

/** Restricts reinforcement to solid block materials, excluding every piston part. */
class ReinforceableBlockValidator @JvmOverloads constructor(
    private val server: Server,
    private val citadels: ReadableCitadelFacade? = null
) : ActionValidator<Action> {
    override val actionClass = Action::class.java

    override fun validate(action: Action): ActionValidator.Result {
        if (action !is PlaceAction && action !is UpgradeAction) return ActionValidator.Result.Neutral
        val block = server.findLoadedPlacementBlock(action, citadels) ?: return ActionValidator.Result.Rejected
        if (!isAllowed(block.type)) return ActionValidator.Result.Rejected
        return if (action is PlaceAction) ActionValidator.Result.Success else ActionValidator.Result.Neutral
    }

    fun isAllowed(material: Material): Boolean {
        if (material == Material.PISTON || material == Material.STICKY_PISTON ||
            material == Material.PISTON_HEAD || material == Material.MOVING_PISTON) return false
        return material.isBlock && material.isSolid
    }
}
