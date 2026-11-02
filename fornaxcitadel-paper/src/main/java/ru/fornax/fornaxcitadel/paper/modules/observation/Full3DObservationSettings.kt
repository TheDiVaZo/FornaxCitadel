package ru.fornax.fornaxcitadel.paper.modules.observation

/** Inclusive radii around the player's horizontal chunk and vertical 16-block section. */
data class Full3DObservationSettings(
    val horizontalRadiusChunks: Int = 3,
    val verticalRadiusSections: Int = 3
) {
    init {
        require(horizontalRadiusChunks >= 0) { "Observation horizontal radius must be non-negative" }
        require(verticalRadiusSections >= 0) { "Observation vertical radius must be non-negative" }
    }
}
