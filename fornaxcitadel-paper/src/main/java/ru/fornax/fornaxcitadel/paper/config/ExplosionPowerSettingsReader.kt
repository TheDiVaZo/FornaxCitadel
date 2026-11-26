package ru.fornax.fornaxcitadel.paper.config

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionPowerSettings
import java.util.Locale

object ExplosionPowerSettingsReader {
    fun read(config: ConfigurationSection): ExplosionPowerSettings {
        val blockPath = "explosions.block-powers"
        val configured = when (val value = config.get(blockPath)) {
            null -> emptyMap<String, Any>()
            is ConfigurationSection -> value.getValues(false)
            is Map<*, *> -> value
            else -> throw IllegalArgumentException("$blockPath must be a mapping of materials to powers")
        }
        val blockPowers = ExplosionPowerSettings.DEFAULT_BLOCK_POWERS.toMutableMap()
        for ((name, value) in configured) {
            require(name is String) { "$blockPath material names must be strings" }
            val material = Material.getMaterial(name.uppercase(Locale.ROOT))
            require(material != null && !material.isLegacy) { "Unknown $blockPath material: $name" }
            blockPowers[material] = readPower(value, "$blockPath.$name")
        }
        return ExplosionPowerSettings(
            defaultEntityPower = config.get("explosions.default-entity-power")?.let {
                readPower(it, "explosions.default-entity-power")
            } ?: 4.0f,
            defaultBlockPower = config.get("explosions.default-block-power")?.let {
                readPower(it, "explosions.default-block-power")
            } ?: 4.0f,
            blockPowers = blockPowers
        )
    }

    private fun readPower(value: Any?, path: String): Float {
        require(value is Number) { "$path must be a number" }
        val number = value.toDouble()
        require(number.isFinite() && number >= 0.0) { "$path must be finite and non-negative" }
        return number.toFloat().also { ExplosionPowerSettings.requirePower(it, path) }
    }
}
