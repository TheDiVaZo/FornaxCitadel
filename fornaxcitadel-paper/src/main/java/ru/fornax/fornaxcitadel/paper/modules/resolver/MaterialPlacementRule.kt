package ru.fornax.fornaxcitadel.paper.modules.resolver

import org.bukkit.block.Block

/**
 * Resolves one material's block relationships on the owning server thread.
 * Rules must only inspect loaded blocks and must not change the world or reinforcement data.
 * A missing reinforcement is different from unavailable data: propagate resolution failures.
 */
interface MaterialPlacementRule {
    fun getResponsibleBlock(block: Block): Block
    fun getRelatedBlock(block: Block): Block?
    /** Yields blocks in lookup order. The first reinforced candidate protects the target. */
    fun getProtectionCandidates(block: Block): Sequence<Block>
}

/** Places on the selected block and checks its related block after a local protection miss. */
open class DefaultMaterialPlacementRule : MaterialPlacementRule {
    override fun getResponsibleBlock(block: Block): Block = block

    override fun getRelatedBlock(block: Block): Block? = null

    override fun getProtectionCandidates(block: Block): Sequence<Block> = sequence {
        yield(block)
        val related = getRelatedBlock(block) ?: return@sequence
        if (related.world.uid == block.world.uid &&
            related.x == block.x && related.y == block.y && related.z == block.z) return@sequence
        yield(related)
    }
}
