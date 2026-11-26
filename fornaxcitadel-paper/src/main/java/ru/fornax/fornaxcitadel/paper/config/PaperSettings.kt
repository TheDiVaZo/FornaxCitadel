package ru.fornax.fornaxcitadel.paper.config

import ru.fornax.fornaxcitadel.paper.modules.acid.AcidSettings
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageSettings
import ru.fornax.fornaxcitadel.paper.modules.dismantle.DismantleSettings
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionPowerSettings
import ru.fornax.fornaxcitadel.paper.modules.observation.PaperObservationSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PlacementSettings

data class PaperSettings(
    val postedActionLimit: Int = 1024,
    val saveIntervalTicks: Long = 1200,
    val playerDamage: PlayerDamageSettings = PlayerDamageSettings(),
    val explosionPowers: ExplosionPowerSettings = ExplosionPowerSettings(),
    val citadelTypes: CitadelTypesSettings = CitadelTypesSettings(),
    val placement: PlacementSettings = PlacementSettings(),
    val observation: PaperObservationSettings = PaperObservationSettings(),
    val dismantle: DismantleSettings = DismantleSettings(),
    val acid: AcidSettings = AcidSettings(),
    val acidConfigurationError: String? = null
) {
    init {
        require(postedActionLimit > 0) { "actions.max-per-tick must be positive" }
        require(saveIntervalTicks > 0) { "storage.save-interval-ticks must be positive" }
    }
}
