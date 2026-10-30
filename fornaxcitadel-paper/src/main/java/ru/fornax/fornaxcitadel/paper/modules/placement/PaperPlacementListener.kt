package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

/** Adapts main-hand item use to the placement service. */
class PaperPlacementListener(private val service: PaperPlacementService) : Listener {
    // A vanilla no-op may deny block interaction while still allowing use of a custom item.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        if (event.useItemInHand() == Event.Result.DENY) return
        val materialKey = service.heldMaterialKey(event.player, event.item) ?: return
        val block = event.clickedBlock ?: return

        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        service.place(event.player, block, materialKey)
    }
}
