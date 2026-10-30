package ru.fornax.fornaxcitadel.paper

import org.bukkit.configuration.ConfigurationSection
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageSettings
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionPowerSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PlacementSettings

data class PaperSettings(
    val postedActionLimit: Int = 1024,
    val saveIntervalTicks: Long = 1200,
    val playerDamage: PlayerDamageSettings = PlayerDamageSettings(),
    val explosionPowers: ExplosionPowerSettings = ExplosionPowerSettings(),
    val citadelTypes: CitadelTypesSettings = CitadelTypesSettings(),
    val placement: PlacementSettings = PlacementSettings()
) {
    init {
        require(postedActionLimit > 0) { "actions.max-per-tick must be positive" }
        require(saveIntervalTicks > 0) { "storage.save-interval-ticks must be positive" }
    }

    companion object {
        fun read(config: ConfigurationSection): PaperSettings {
            return PaperSettings(
                postedActionLimit = config.getInt("actions.max-per-tick", 1024),
                saveIntervalTicks = config.getLong("storage.save-interval-ticks", 1200),
                playerDamage = PlayerDamageSettings.read(config),
                explosionPowers = ExplosionPowerSettings.read(config),
                citadelTypes = CitadelTypesSettings.read(config),
                placement = PlacementSettings.read(config)
            )
        }
    }
}
