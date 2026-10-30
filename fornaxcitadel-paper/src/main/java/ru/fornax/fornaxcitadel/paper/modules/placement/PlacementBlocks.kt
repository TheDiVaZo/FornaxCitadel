package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.Server
import org.bukkit.block.Block
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.modules.place.PlaceAction

/** Validators never load a world or chunk just to inspect a placement target. */
internal fun Server.findLoadedPlacementBlock(action: PlaceAction): Block? {
    if (!CitadelPosition.isSupported(action.citadelX, action.citadelY, action.citadelZ)) return null
    val world = getWorld(action.citadelW) ?: return null
    if (action.citadelY !in world.minHeight until world.maxHeight ||
        !world.isChunkLoaded(action.citadelX shr 4, action.citadelZ shr 4)) return null
    return world.getBlockAt(action.citadelX, action.citadelY, action.citadelZ)
}
