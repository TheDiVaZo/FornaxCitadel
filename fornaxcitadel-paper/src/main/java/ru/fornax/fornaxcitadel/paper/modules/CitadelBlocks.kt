package ru.fornax.fornaxcitadel.paper.modules

import org.bukkit.block.Block
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.CitadelResolutionUnavailableException

/** Outside core's coordinate bounds no citadel can exist, so vanilla behavior stays available. */
internal fun Block.supportsCitadelData(): Boolean =
    CitadelPosition.isSupported(x, y, z)

/** Protect unavailable chunk data until it can be loaded without losing reinforcement. */
internal fun CitadelFacade.isProtected(block: Block): Boolean {
    if (!block.supportsCitadelData()) return false
    return try {
        findProtecting(CitadelPosition(block.world.uid, block.x, block.y, block.z)) != null
    } catch (_: CitadelResolutionUnavailableException) {
        true
    }
}
