package ru.fornax.fornaxcitadel.paper.modules.observation

import org.bukkit.configuration.ConfigurationSection

data class PaperObservationSettings(
    val intervalTicks: Long = 1,
    val full3D: Full3DObservationSettings = Full3DObservationSettings()
) {
    init {
        require(intervalTicks > 0) { "observation.interval-ticks must be positive" }
    }

    companion object {
        fun read(config: ConfigurationSection) = PaperObservationSettings(
            intervalTicks = config.getLong("observation.interval-ticks", 1),
            full3D = Full3DObservationSettings(
                horizontalRadiusChunks = config.getInt("observation.full-3d.horizontal-radius-chunks", 3),
                verticalRadiusSections = config.getInt("observation.full-3d.vertical-radius-sections", 3)
            )
        )
    }
}
