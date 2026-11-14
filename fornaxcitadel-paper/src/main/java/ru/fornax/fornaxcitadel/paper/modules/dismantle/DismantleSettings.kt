package ru.fornax.fornaxcitadel.paper.modules.dismantle

import org.bukkit.configuration.ConfigurationSection

/** Refund probability for damaged citadels; full health or no Health component guarantees a refund. */
data class DismantleSettings(val refundChance: Double = 0.5) {
    init {
        require(refundChance.isFinite() && refundChance in 0.0..1.0) {
            "dismantle.refund-chance must be finite and between 0 and 1"
        }
    }

    companion object {
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
}
