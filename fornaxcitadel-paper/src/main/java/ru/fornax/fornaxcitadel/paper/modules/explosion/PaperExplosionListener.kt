package ru.fornax.fornaxcitadel.paper.modules.explosion

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.ExplosionPrimeEvent

class PaperExplosionListener(private val service: PaperExplosionService) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onExplosionPrime(event: ExplosionPrimeEvent) {
        if (event.isCancelled) service.forgetPower(event.entity.uniqueId)
        else service.recordPower(event.entity.uniqueId, event.radius)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityExplosion(event: EntityExplodeEvent) {
        try {
            if (!event.isCancelled) {
                service.damageFromEntity(event.entity, event.location, event.blockList())
            }
        } finally {
            service.forgetPower(event.entity.uniqueId)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) {
        service.damageFromBlock(event.explodedBlockState, event.blockList())
    }

    // Paper has already removed a bed/anchor source, even when the explosion is cancelled.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onExplosionSourceRemoved(event: BlockExplodeEvent) {
        service.reconcileRemovedSource(event.explodedBlockState)
    }
}
