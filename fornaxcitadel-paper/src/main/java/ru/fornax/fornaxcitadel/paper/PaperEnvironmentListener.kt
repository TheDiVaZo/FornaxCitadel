package ru.fornax.fornaxcitadel.paper

import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager

/** Keeps reinforcement attached to its block until a core action removes it. */
class PaperEnvironmentListener(private val manager: SimpleComponentManager) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplosion(event: EntityExplodeEvent) {
        event.blockList().removeIf(::isProtected)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) {
        event.blockList().removeIf(::isProtected)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (isProtected(event.block) || isProtected(event.block.getRelative(event.direction)) ||
            movesProtectedBlock(event.blocks, event.direction)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        val facing = (event.block.blockData as? Directional)?.facing ?: event.direction
        if (isProtected(event.block) || isProtected(event.block.getRelative(facing)) ||
            movesProtectedBlock(event.blocks, facing.oppositeFace)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        if (isProtected(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) {
        if (isProtected(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onForm(event: BlockFormEvent) {
        if (isProtected(event.block)) event.isCancelled = true
    }

    // Spread has its own Bukkit HandlerList despite extending BlockFormEvent.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSpread(event: BlockSpreadEvent) {
        if (isProtected(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFlow(event: BlockFromToEvent) {
        if (isProtected(event.block) || isProtected(event.toBlock)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityChange(event: EntityChangeBlockEvent) {
        if (isProtected(event.block)) event.isCancelled = true
    }

    private fun movesProtectedBlock(blocks: List<Block>, direction: BlockFace): Boolean =
        blocks.any { isProtected(it) || isProtected(it.getRelative(direction)) }

    private fun isProtected(block: Block): Boolean {
        if (!block.supportsCitadelData()) return false
        val world = block.world.uid
        return !manager.isChunkLoaded(world, block.x shr 4, block.z shr 4) ||
            manager.findCitadel(world, block.x, block.y, block.z) != null
    }
}
