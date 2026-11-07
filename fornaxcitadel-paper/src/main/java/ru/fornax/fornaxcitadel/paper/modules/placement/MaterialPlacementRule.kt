package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.block.Block
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle

/**
 * Resolves one material's block relationships on the owning server thread.
 * Rules must only inspect loaded blocks and must not change the world or reinforcement data.
 * A missing reinforcement is different from unavailable data: propagate resolution failures.
 */
interface MaterialPlacementRule {
    fun getResponsibleBlock(block: Block): Block
    fun getRelatedBlock(block: Block): Block?
    fun getReinforcementProtecting(block: Block): CitadelHandle?
}

/**
 * Identity placement with local-first protection. Subclasses can override either relationship.
 * [findExact] must perform an availability-checked, exact lookup, never another rule resolution.
 */
open class DefaultMaterialPlacementRule(
    private val findExact: (Block) -> CitadelHandle?
) : MaterialPlacementRule {
    override fun getResponsibleBlock(block: Block): Block = block

    override fun getRelatedBlock(block: Block): Block? = null

    override fun getReinforcementProtecting(block: Block): CitadelHandle? {
        findExact(block)?.let { return it }
        val related = getRelatedBlock(block) ?: return null
        if (related.world.uid == block.world.uid &&
            related.x == block.x && related.y == block.y && related.z == block.z) return null
        return findExact(related)
    }
}
