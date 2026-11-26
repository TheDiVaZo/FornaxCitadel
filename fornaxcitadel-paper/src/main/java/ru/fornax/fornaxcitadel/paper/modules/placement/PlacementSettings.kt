package ru.fornax.fornaxcitadel.paper.modules.placement

import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.material.BukkitMaterialRegistry

/** Material identities are resolved when validating placement, so providers may register later. */
data class PlacementSettings(
    val listType: BlockListType = BlockListType.WHITELIST,
    val blocks: Set<Key> = DEFAULT_BLOCKS
) {
    init {
        blocks.forEach(::requireValidKey)
    }

    companion object {
        val DEFAULT_BLOCKS: Set<Key> = listOf(
            "stone", "cobblestone", "stone_bricks",
            "deepslate", "cobbled_deepslate", "deepslate_bricks",
            "bricks", "obsidian", "crying_obsidian",
            "blackstone", "polished_blackstone", "nether_bricks",
            "end_stone", "iron_block", "gold_block", "diamond_block",
            "emerald_block", "netherite_block"
        ).mapTo(linkedSetOf()) { Key("minecraft", it) }

        internal fun requireValidKey(key: Key) {
            require(key.namespace.isNotBlank() && key.key.isNotBlank()) {
                "Placement material keys must contain both namespace and value: $key"
            }
            require(key.namespace != "minecraft" || BukkitMaterialRegistry.getType(key) != null) {
                "Unknown Bukkit placement material: $key"
            }
        }
    }
}
