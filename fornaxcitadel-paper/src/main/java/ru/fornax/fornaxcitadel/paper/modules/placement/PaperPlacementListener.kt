package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

/** Routes main-hand reinforcement item use to placement or repair. */
class PaperPlacementListener(private val service: PaperPlacementService) : Listener {
    // A vanilla no-op may deny block interaction while still allowing use of a custom item.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        if (event.useItemInHand() == Event.Result.DENY) return
        service.interact(event)
    }
}
