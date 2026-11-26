package ru.fornax.fornaxcitadel.paper.modules.explosion

import org.bukkit.Material

/** Source strengths used when no entity prime radius is available. */
data class ExplosionPowerSettings(
    val defaultEntityPower: Float = 4.0f,
    val defaultBlockPower: Float = 4.0f,
    val blockPowers: Map<Material, Float> = DEFAULT_BLOCK_POWERS
) {
    init {
        requirePower(defaultEntityPower, "explosions.default-entity-power")
        requirePower(defaultBlockPower, "explosions.default-block-power")
        for ((material, power) in blockPowers) requirePower(power, "explosions.block-powers.${material.name}")
    }

    fun getBlockPower(material: Material): Float = blockPowers[material] ?: defaultBlockPower

    companion object {
        val DEFAULT_BLOCK_POWERS: Map<Material, Float> = Material.entries
            .filter { !it.isLegacy && it.name.endsWith("_BED") }
            .associateWith { 5.0f } + (Material.RESPAWN_ANCHOR to 5.0f)

        internal fun requirePower(power: Float, path: String) {
            require(power.isFinite() && power >= 0.0f) { "$path must be finite and non-negative" }
        }
    }
}
