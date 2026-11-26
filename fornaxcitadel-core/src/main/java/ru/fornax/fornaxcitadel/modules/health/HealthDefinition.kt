package ru.fornax.fornaxcitadel.modules.health

/** Current damage protection and optional maximum HP for a type. */
data class HealthDefinition @JvmOverloads constructor(val maxHealth: Double?, val invulnerable: Boolean = false) {
    init {
        require(maxHealth != null || invulnerable) { "A damageable health profile requires maximum health" }
        require(maxHealth == null || maxHealth.isFinite() && maxHealth > 0.0) { "Maximum health must be finite and positive" }
    }
}
