package ru.fornax.fornaxcitadel.paper.config

import org.bukkit.configuration.ConfigurationSection
import ru.fornax.fornaxcitadel.paper.modules.dismantle.DismantleSettings

object DismantleSettingsReader {
    fun read(config: ConfigurationSection): DismantleSettings {
        val configured = when (val section = config.get("dismantle", null)) {
            null -> 0.5
            is ConfigurationSection -> section.get("refund-chance", 0.5)
            is Map<*, *> -> section["refund-chance"] ?: 0.5
            else -> throw IllegalArgumentException("dismantle must be a mapping")
        }
        require(configured is Number) { "dismantle.refund-chance must be a number" }
        return DismantleSettings(configured.toDouble())
    }
}
