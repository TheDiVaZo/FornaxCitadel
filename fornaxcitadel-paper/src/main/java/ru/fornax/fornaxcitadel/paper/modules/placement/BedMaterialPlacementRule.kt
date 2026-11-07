package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Bed
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle

/** Both bed halves place at the head; old reinforcement on either half stays accessible. */
class BedMaterialPlacementRule(
    findExact: (Block) -> CitadelHandle?,
    private val findRelative: (Block, BlockFace) -> Block?
) : DefaultMaterialPlacementRule(findExact) {
    override fun getResponsibleBlock(block: Block): Block {
        val bed = block.blockData as? Bed ?: return block
        return if (bed.part == Bed.Part.FOOT) getRelatedBlock(block) ?: block else block
    }

    override fun getRelatedBlock(block: Block): Block? {
        val bed = block.blockData as? Bed ?: return null
        val direction = if (bed.part == Bed.Part.HEAD) bed.facing.oppositeFace else bed.facing
        val other = findRelative(block, direction) ?: return null
        if (other.type != block.type) return null
        val partner = other.blockData as? Bed ?: return null
        return other.takeIf { partner.part != bed.part && partner.facing == bed.facing }
    }
}
