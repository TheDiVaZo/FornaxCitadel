package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.Server
import org.bukkit.block.Block
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.modules.place.PlaceAction

/** Validators never load a world or chunk just to inspect a placement target. */
internal fun Server.findLoadedPlacementBlock(action: PlaceAction, citadels: CitadelFacade? = null): Block? {
    if (!CitadelPosition.isSupported(action.citadelX, action.citadelY, action.citadelZ)) return null
    val position = try {
        citadels?.getResponsiblePosition(action.position) ?: action.position
    } catch (_: CitadelResolutionUnavailableException) {
        return null
    }
    if (!CitadelPosition.isSupported(position.x, position.y, position.z)) return null
    val world = getWorld(position.world) ?: return null
    if (position.y !in world.minHeight until world.maxHeight ||
        !world.isChunkLoaded(position.x shr 4, position.z shr 4)) return null
    return world.getBlockAt(position.x, position.y, position.z)
}
