package ru.fornax.fornaxcitadel.modules.health

/** Current configured maximum; existing HP is never rewritten when this profile changes. */
data class HealthDefinition(val maxHealth: Double) {
    init {
        require(maxHealth.isFinite() && maxHealth > 0.0) { "Maximum health must be finite and positive" }
    }
}
