package ru.fornax.fornaxcitadel.paper.config

import org.bukkit.configuration.ConfigurationSection
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidSettings

object PaperSettingsReader {
    fun read(config: ConfigurationSection): PaperSettings {
        val acid = try {
            Result.success(AcidSettingsReader.read(config))
        } catch (failure: IllegalArgumentException) {
            Result.failure(failure)
        }
        return PaperSettings(
            postedActionLimit = config.getInt("actions.max-per-tick", 1024),
            saveIntervalTicks = config.getLong("storage.save-interval-ticks", 1200),
            playerDamage = PlayerDamageSettingsReader.read(config),
            explosionPowers = ExplosionPowerSettingsReader.read(config),
            citadelTypes = CitadelTypesSettingsReader.read(config),
            placement = PlacementSettingsReader.read(config),
            observation = PaperObservationSettingsReader.read(config),
            dismantle = DismantleSettingsReader.read(config),
            acid = acid.getOrElse { AcidSettings(enabled = false, types = emptyList()) },
            acidConfigurationError = acid.exceptionOrNull()?.message
        )
    }
}
