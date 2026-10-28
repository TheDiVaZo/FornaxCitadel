package ru.fornax.fornaxcitadel.modules.fortification

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthBehavior
import kotlin.math.min

/**
 * Maximum health indexed by reinforcement level, starting at level one. Configuration is
 * copied on construction. Live updates preserve the remaining health fraction; they do not
 * restart maturation or revive destroyed health. The caller tracks dirty health changes.
 */
class FortifiedHealthBehavior(maxHealthByLevel: List<Double>) : HealthBehavior {
    private val maxima = maxHealthByLevel.toList()
    val maximumLevel: Int get() = maxima.size

    init {
        require(maxima.isNotEmpty()) { "At least one fortification level is required" }
        for ((index, maximum) in maxima.withIndex()) {
            require(maximum.isFinite() && maximum > 0.0) {
                "Maximum health at level ${index + 1} must be finite and positive: $maximum"
            }
            require(index == 0 || maximum >= maxima[index - 1]) {
                "Maximum health must not decrease with fortification level"
            }
        }
    }

    override fun initializeHealth(component: Health, draft: CitadelDraft) {
        val fortification = checkNotNull(draft.getComponent(Fortification::class.java)) {
            "Fortification must be initialized before health"
        }
        val maximum = maximumFor(fortification.level)
        component.maxHealth = maximum
        component.health = maximum
    }

    override fun updateHealth(component: Health, citadel: CitadelHandle): Boolean {
        val previousMaximum = component.maxHealth
        val previousHealth = component.health
        require(previousMaximum.isFinite() && previousMaximum > 0.0) {
            "Previous maximum health must be finite and positive: $previousMaximum"
        }
        require(previousHealth.isFinite() && previousHealth >= 0.0) {
            "Previous health must be finite and non-negative: $previousHealth"
        }
        val fortification = checkNotNull(citadel.getComponent(Fortification::class.java)) {
            "Fortification component is missing"
        }
        val maximum = maximumFor(fortification.level)
        val wasFull = previousHealth > 0.0 && previousHealth >= previousMaximum - FULL_HEALTH_TOLERANCE
        val health = when {
            wasFull -> maximum
            maximum == previousMaximum -> previousHealth
            else -> min(previousHealth / previousMaximum * maximum, maximum)
        }
        if (maximum == previousMaximum && health == previousHealth) return false
        component.maxHealth = maximum
        component.health = health
        return true
    }

    private fun maximumFor(level: Int): Double {
        require(level in 1..maximumLevel) { "Unsupported fortification level: $level (maximum $maximumLevel)" }
        return maxima[level - 1]
    }

    private companion object {
        const val FULL_HEALTH_TOLERANCE = 1e-9
    }
}
