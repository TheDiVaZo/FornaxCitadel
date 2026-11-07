package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Chest
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle

/**
 * Double chests place on the selected half; existing reinforcement on either side takes local priority.
 * Copper halves may have different oxidation and wax states. Unconnected chests remain independent.
 */
class ChestMaterialPlacementRule(
    findExact: (Block) -> CitadelHandle?,
    private val findRelative: (Block, BlockFace) -> Block?
) : DefaultMaterialPlacementRule(findExact) {
    override fun getRelatedBlock(block: Block): Block? {
        if (!supports(block.type)) return null
        val chest = block.blockData as? Chest ?: return null
        if (chest.type == Chest.Type.SINGLE) return null
        // Chest LEFT/RIGHT are defined from the chest's perspective, not the viewer's.
        val clockwise = when (chest.facing) {
            BlockFace.NORTH -> BlockFace.EAST
            BlockFace.EAST -> BlockFace.SOUTH
            BlockFace.SOUTH -> BlockFace.WEST
            BlockFace.WEST -> BlockFace.NORTH
            else -> return null
        }
        val direction = if (chest.type == Chest.Type.LEFT) clockwise else clockwise.oppositeFace
        val other = findRelative(block, direction) ?: return null
        if (other.type != block.type && !(isCopperChest(block.type) && isCopperChest(other.type))) return null
        val partner = other.blockData as? Chest ?: return null
        val expectedPart = if (chest.type == Chest.Type.LEFT) Chest.Type.RIGHT else Chest.Type.LEFT
        return other.takeIf { partner.type == expectedPart && partner.facing == chest.facing }
    }

    internal companion object {
        fun supports(material: Material): Boolean =
            material == Material.CHEST || material == Material.TRAPPED_CHEST || isCopperChest(material)

        private fun isCopperChest(material: Material): Boolean =
            material.name == "COPPER_CHEST" || material.name.endsWith("_COPPER_CHEST")
    }
}
