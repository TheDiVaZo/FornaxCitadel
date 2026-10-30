package ru.fornax.fornaxcitadel.paper.modules.explosion

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import java.util.Locale

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

        fun read(root: ConfigurationSection): ExplosionPowerSettings {
            val blockPath = "explosions.block-powers"
            val configured = when (val value = root.get(blockPath)) {
                null -> emptyMap<String, Any>()
                is ConfigurationSection -> value.getValues(false)
                is Map<*, *> -> value
                else -> throw IllegalArgumentException("$blockPath must be a mapping of materials to powers")
            }
            val blockPowers = DEFAULT_BLOCK_POWERS.toMutableMap()
            for ((name, value) in configured) {
                require(name is String) { "$blockPath material names must be strings" }
                val material = Material.getMaterial(name.uppercase(Locale.ROOT))
                require(material != null && !material.isLegacy) { "Unknown $blockPath material: $name" }
                blockPowers[material] = readPower(value, "$blockPath.$name")
            }
            return ExplosionPowerSettings(
                defaultEntityPower = root.get("explosions.default-entity-power")?.let {
                    readPower(it, "explosions.default-entity-power")
                } ?: 4.0f,
                defaultBlockPower = root.get("explosions.default-block-power")?.let {
                    readPower(it, "explosions.default-block-power")
                } ?: 4.0f,
                blockPowers = blockPowers
            )
        }

        private fun readPower(value: Any?, path: String): Float {
            require(value is Number) { "$path must be a number" }
            val number = value.toDouble()
            require(number.isFinite() && number >= 0.0) { "$path must be finite and non-negative" }
            return number.toFloat().also { requirePower(it, path) }
        }

        private fun requirePower(power: Float, path: String) {
            require(power.isFinite() && power >= 0.0f) { "$path must be finite and non-negative" }
        }
    }
}
