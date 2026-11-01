package ru.fornax.fornaxcitadel.paper.modules.environment

import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.paper.modules.isProtected

/** Keeps environmental changes from moving or replacing reinforced blocks. */
class PaperEnvironmentService(
    private val plugin: Plugin,
    module: CitadelModule
) : LifecycleService {
    private val manager = module.actions.componentManager
    private val listener = PaperEnvironmentListener(this)
    private var enabled = false

    override fun enable() {
        if (enabled) return
        try {
            plugin.server.pluginManager.registerEvents(listener, plugin)
            enabled = true
        } catch (failure: Throwable) {
            HandlerList.unregisterAll(listener)
            throw failure
        }
    }

    override fun disable() {
        if (!enabled) return
        HandlerList.unregisterAll(listener)
        enabled = false
    }

    fun isProtected(block: Block): Boolean = manager.isProtected(block)

    fun blocksPistonExtension(piston: Block, blocks: List<Block>, direction: BlockFace): Boolean =
        isProtected(piston) || isProtected(piston.getRelative(direction)) ||
            movesProtectedBlock(blocks, direction)

    fun blocksPistonRetraction(piston: Block, blocks: List<Block>, direction: BlockFace): Boolean {
        val facing = (piston.blockData as? Directional)?.facing ?: direction
        return isProtected(piston) || isProtected(piston.getRelative(facing)) ||
            movesProtectedBlock(blocks, facing.oppositeFace)
    }

    fun blocksFlow(from: Block, to: Block): Boolean = isProtected(from) || isProtected(to)

    private fun movesProtectedBlock(blocks: List<Block>, direction: BlockFace): Boolean =
        blocks.any { isProtected(it) || isProtected(it.getRelative(direction)) }
}
