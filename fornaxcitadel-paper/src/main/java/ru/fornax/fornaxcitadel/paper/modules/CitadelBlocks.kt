package ru.fornax.fornaxcitadel.paper.modules

import org.bukkit.block.Block
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager

/** Outside core's coordinate bounds no citadel can exist, so vanilla behavior stays available. */
internal fun Block.supportsCitadelData(): Boolean =
    CitadelPosition.isSupported(x, y, z)

/** Protect unavailable chunk data until it can be loaded without losing reinforcement. */
internal fun SimpleComponentManager.isProtected(block: Block): Boolean {
    if (!block.supportsCitadelData()) return false
    val world = block.world.uid
    return !isChunkLoaded(world, block.x shr 4, block.z shr 4) ||
        findCitadel(world, block.x, block.y, block.z) != null
}
