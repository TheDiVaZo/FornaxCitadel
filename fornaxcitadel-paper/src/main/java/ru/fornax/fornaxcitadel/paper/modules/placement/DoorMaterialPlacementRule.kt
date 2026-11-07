package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Door
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle

/** Both door halves place on the bottom half; old reinforcement on either half stays accessible. */
class DoorMaterialPlacementRule(
    findExact: (Block) -> CitadelHandle?,
    private val findRelative: (Block, BlockFace) -> Block?
) : DefaultMaterialPlacementRule(findExact) {
    override fun getResponsibleBlock(block: Block): Block {
        val door = block.blockData as? Door ?: return block
        return if (door.half == Bisected.Half.TOP) getRelatedBlock(block) ?: block else block
    }

    override fun getRelatedBlock(block: Block): Block? {
        val door = block.blockData as? Door ?: return null
        val direction = if (door.half == Bisected.Half.TOP) BlockFace.DOWN else BlockFace.UP
        val other = findRelative(block, direction) ?: return null
        if (other.type != block.type) return null
        val partner = other.blockData as? Door ?: return null
        return other.takeIf { partner.half != door.half && partner.facing == door.facing }
    }
}
