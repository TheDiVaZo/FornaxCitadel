package ru.fornax.fornaxcitadel.paper.modules.acid

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition

/**
 * Synchronous pre-destruction event for one physical part of an acid target.
 * Cancelling any part preserves that logical target; other targets are processed independently.
 * The source is consumed by activation and does not receive this event.
 * The inherited handler list also delivers this event to ordinary [BlockBreakEvent] listeners.
 */
class AcidBlockDissolveEvent(
    block: Block,
    player: Player,
    val acidPosition: CitadelPosition,
    val acidType: AcidType
) : BlockBreakEvent(block, player)
