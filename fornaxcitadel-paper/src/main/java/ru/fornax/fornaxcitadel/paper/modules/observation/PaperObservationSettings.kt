package ru.fornax.fornaxcitadel.paper.modules.observation

data class PaperObservationSettings(
    val intervalTicks: Long = 1,
    val full3D: Full3DObservationSettings = Full3DObservationSettings()
) {
    init {
        require(intervalTicks > 0) { "observation.interval-ticks must be positive" }
    }
}
