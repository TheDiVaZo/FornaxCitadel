package ru.fornax.fornaxcitadel.paper

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

data class PaperSettings(
    val postedActionLimit: Int = 1024,
    val saveIntervalTicks: Long = 1200,
    val damagePerBreak: Double = 1.0,
    val fortifiedMaxHealth: List<Double> = listOf(20.0, 40.0, 80.0),
    val initialMaxHealth: Double = 10.0,
    val matureMaxHealth: Double = 40.0,
    val maturationDurationMillis: Long = 600_000,
    val devMaxHealth: Double = 20.0,
    val allowedBlocks: Set<Material> = SUPPORTED_BLOCKS
) {
    init {
        require(postedActionLimit > 0) { "actions.max-per-tick must be positive" }
        require(saveIntervalTicks > 0) { "storage.save-interval-ticks must be positive" }
        require(damagePerBreak.isFinite() && damagePerBreak > 0.0) { "damage-per-break must be positive and finite" }
        require(fortifiedMaxHealth.isNotEmpty() && fortifiedMaxHealth.all { it.isFinite() && it > 0.0 }) {
            "health.reinforced must contain positive finite maxima"
        }
        require(fortifiedMaxHealth.zipWithNext().all { (a, b) -> b >= a }) {
            "health.reinforced maxima must not decrease with level"
        }
        require(initialMaxHealth.isFinite() && initialMaxHealth > 0.0 &&
            matureMaxHealth.isFinite() && matureMaxHealth >= initialMaxHealth) { "Invalid maturing health range" }
        require(maturationDurationMillis > 0) { "health.maturing.duration-seconds must be positive" }
        require(devMaxHealth.isFinite() && devMaxHealth > 0.0) { "health.dev must be positive and finite" }
        require(allowedBlocks.isNotEmpty() && SUPPORTED_BLOCKS.containsAll(allowedBlocks)) {
            "allowed-blocks must be a non-empty subset of the supported durable blocks: $SUPPORTED_BLOCKS"
        }
    }

    companion object {
        // Durable full blocks only. Supporting containers, multipart blocks and gravity needs
        // additional lifecycle rules rather than accepting arbitrary Material.isSolid blocks.
        val SUPPORTED_BLOCKS: Set<Material> = setOf(
            Material.STONE, Material.COBBLESTONE, Material.STONE_BRICKS,
            Material.DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.DEEPSLATE_BRICKS,
            Material.BRICKS, Material.OBSIDIAN, Material.CRYING_OBSIDIAN,
            Material.BLACKSTONE, Material.POLISHED_BLACKSTONE, Material.NETHER_BRICKS,
            Material.END_STONE, Material.IRON_BLOCK, Material.GOLD_BLOCK, Material.DIAMOND_BLOCK,
            Material.EMERALD_BLOCK, Material.NETHERITE_BLOCK
        )

        fun read(config: ConfigurationSection): PaperSettings {
            val allowed = config.getStringList("allowed-blocks").map { name ->
                requireNotNull(Material.matchMaterial(name)) { "Unknown allowed-blocks material: $name" }
            }.toSet()
            return PaperSettings(
                postedActionLimit = config.getInt("actions.max-per-tick", 1024),
                saveIntervalTicks = config.getLong("storage.save-interval-ticks", 1200),
                damagePerBreak = config.getDouble("damage-per-break", 1.0),
                fortifiedMaxHealth = config.getDoubleList("health.reinforced"),
                initialMaxHealth = config.getDouble("health.maturing.initial", 10.0),
                matureMaxHealth = config.getDouble("health.maturing.mature", 40.0),
                maturationDurationMillis = Math.multiplyExact(config.getLong("health.maturing.duration-seconds", 600), 1000),
                devMaxHealth = config.getDouble("health.dev", 20.0),
                allowedBlocks = allowed
            )
        }
    }
}
