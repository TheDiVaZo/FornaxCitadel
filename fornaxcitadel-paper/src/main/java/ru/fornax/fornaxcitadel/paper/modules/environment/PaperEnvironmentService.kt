package ru.fornax.fornaxcitadel.paper.modules.environment

import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.paper.modules.isProtected

/** Keeps environmental changes from moving or replacing reinforced blocks. */
class PaperEnvironmentService {
    private lateinit var manager: SimpleComponentManager

    fun init(plugin: Plugin, module: CitadelModule) {
        check(!::manager.isInitialized) { "Environment service is already initialized" }
        manager = module.actions.componentManager
        plugin.server.pluginManager.registerEvents(PaperEnvironmentListener(this), plugin)
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
