package ru.fornax.fornaxcitadel.paper.modules.breaking

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent

/** Applies the breaking service's decisions to Bukkit mining events. */
class PaperBreakingListener(private val service: PaperBreakingService) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: BlockDamageEvent) {
        try {
            when (service.prepareBreak(event.player, event.block)) {
                PaperBreakingService.MiningDecision.DEFAULT -> Unit
                // This only skips the vanilla mining delay. onBreak still authorizes the action.
                PaperBreakingService.MiningDecision.INSTANT -> event.instaBreak = true
                PaperBreakingService.MiningDecision.DENY -> event.isCancelled = true
            }
        } catch (exception: Exception) {
            event.isCancelled = true
            throw exception
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        // Keep the physical block safe during the action and if even error reporting fails.
        event.isCancelled = true
        event.isCancelled = service.breakBlock(event.player, event.block)
    }
}
