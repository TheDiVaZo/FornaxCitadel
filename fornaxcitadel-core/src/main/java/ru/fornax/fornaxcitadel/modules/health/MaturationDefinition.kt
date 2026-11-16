package ru.fornax.fornaxcitadel.modules.health

import java.time.Duration

/** Live settings for a citadel definition; never persisted in a citadel component. */
data class MaturationDefinition(val duration: Duration, val initialDamageMultiplier: Double = 1.0) {
    init {
        require(!duration.isNegative) { "Maturation duration must be non-negative" }
        require(initialDamageMultiplier.isFinite() && initialDamageMultiplier >= 1.0) {
            "Initial damage multiplier must be finite and at least one"
        }
    }
}
