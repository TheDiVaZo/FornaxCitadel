package ru.fornax.fornaxcitadel.paper.config

import org.bukkit.configuration.ConfigurationSection
import ru.fornax.fornaxcitadel.paper.modules.observation.Full3DObservationSettings
import ru.fornax.fornaxcitadel.paper.modules.observation.PaperObservationSettings

object PaperObservationSettingsReader {
    fun read(config: ConfigurationSection): PaperObservationSettings = PaperObservationSettings(
        intervalTicks = config.getLong("observation.interval-ticks", 1),
        full3D = Full3DObservationSettings(
            horizontalRadiusChunks = config.getInt("observation.full-3d.horizontal-radius-chunks", 3),
            verticalRadiusSections = config.getInt("observation.full-3d.vertical-radius-sections", 3)
        )
    )
}
