package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.Material
import org.bukkit.Server
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.place.PlaceAction

/** Restricts reinforcement to solid block materials, excluding every piston part. */
class ReinforceableBlockValidator(private val server: Server) : ActionValidator<PlaceAction> {
    override val actionClass = PlaceAction::class.java

    override fun validate(action: PlaceAction): ActionValidator.Result {
        val block = server.findLoadedPlacementBlock(action) ?: return ActionValidator.Result.Rejected
        return if (isAllowed(block.type)) ActionValidator.Result.Success else ActionValidator.Result.Rejected
    }

    fun isAllowed(material: Material): Boolean {
        if (material == Material.PISTON || material == Material.STICKY_PISTON ||
            material == Material.PISTON_HEAD || material == Material.MOVING_PISTON) return false
        return material.isBlock && material.isSolid
    }
}
