package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import java.time.Clock
import kotlin.math.min

/**
 * Grows maximum health linearly from [initialMaxHealth] to [matureMaxHealth].
 * [initializeHealth] prepares the current Unix epoch time in a draft's [Maturing] component.
 * Register [Maturing] using [Maturing.KEY] before loading chunks, as with [Health].
 * [maturationDurationMillis] must be positive.
 * Future start times use the initial maximum; completed maturation uses the mature maximum.
 *
 * Updates preserve the remaining health fraction. A full component stays full, and a
 * destroyed component stays destroyed. The caller must mark the component dirty when
 * [updateHealth] returns true, or perform the update inside an edit operation.
 */
class MaturingHealthBehavior @JvmOverloads constructor(
    private val initialMaxHealth: Double,
    private val matureMaxHealth: Double,
    private val maturationDurationMillis: Long,
    private val clock: Clock = Clock.systemUTC()
) : HealthBehavior {
    init {
        require(initialMaxHealth.isFinite() && initialMaxHealth > 0.0) {
            "Initial maximum health must be a finite positive value: $initialMaxHealth"
        }
        require(matureMaxHealth.isFinite() && matureMaxHealth >= initialMaxHealth) {
            "Mature maximum health must be finite and at least $initialMaxHealth: $matureMaxHealth"
        }
        require(maturationDurationMillis > 0) {
            "Maturation duration must be positive: $maturationDurationMillis"
        }
    }

    /** Starts maturation at full initial health without changing live storage. */
    override fun initializeHealth(component: Health, draft: CitadelDraft) {
        draft.addComponent(Maturing::class.java, Maturing(clock.millis()))
        component.maxHealth = initialMaxHealth
        component.health = initialMaxHealth
    }

    /** Returns true only when the maximum or current health actually changes. */
    override fun updateHealth(component: Health, citadel: CitadelHandle): Boolean {
        val previousMaxHealth = component.maxHealth
        val previousHealth = component.health
        require(previousMaxHealth.isFinite() && previousMaxHealth > 0.0) {
            "Previous maximum health must be a finite positive value: $previousMaxHealth"
        }
        require(previousHealth.isFinite() && previousHealth >= 0.0) {
            "Previous health must be a finite non-negative value: $previousHealth"
        }

        val maturing = checkNotNull(citadel.getComponent(Maturing::class.java)) {
            "Maturing component is missing; call initializeHealth before updateHealth"
        }
        val maxHealth = calculateMaxHealth(maturing.startedAtMillis)
        val wasFullHealth = previousHealth > 0.0 &&
            previousHealth >= previousMaxHealth - FULL_HEALTH_TOLERANCE
        val health = when {
            wasFullHealth -> maxHealth
            maxHealth == previousMaxHealth -> previousHealth
            else -> min(previousHealth / previousMaxHealth * maxHealth, maxHealth)
        }
        if (maxHealth == previousMaxHealth && health == previousHealth) return false

        component.maxHealth = maxHealth
        component.health = health
        return true
    }

    private fun calculateMaxHealth(startedAt: Long): Double {
        val now = clock.millis()
        if (now <= startedAt) return initialMaxHealth

        val elapsed = now - startedAt
        // A negative difference here means a positive elapsed duration overflowed Long.
        if (elapsed < 0 || elapsed >= maturationDurationMillis) return matureMaxHealth

        val progress = elapsed.toDouble() / maturationDurationMillis
        return initialMaxHealth + (matureMaxHealth - initialMaxHealth) * progress
    }

    private companion object {
        const val FULL_HEALTH_TOLERANCE = 1e-9
    }
}
