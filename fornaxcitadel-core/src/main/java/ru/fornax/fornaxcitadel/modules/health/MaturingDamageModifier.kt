package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import java.time.Clock

/** Amplifies damage to reproduce a linearly growing effective maximum without changing stored HP. */
class MaturingDamageModifier @JvmOverloads constructor(
    private val clock: Clock = Clock.systemUTC()
) : DamageModifier {
    override fun getDamage(context: ActionContext, citadel: CitadelHandle, currentDamage: Double): Double {
        require(currentDamage.isFinite() && currentDamage >= 0.0) {
            "Damage must be finite and non-negative"
        }
        val maturing = citadel.getComponent(Maturing::class.java) ?: return currentDamage
        maturing.validate()
        if (currentDamage == 0.0) return currentDamage

        val now = clock.millis()
        val progress = if (now <= maturing.startedAtMillis) {
            0.0
        } else {
            val elapsed = now - maturing.startedAtMillis
            // A negative difference here means a positive elapsed duration overflowed Long.
            if (elapsed < 0 || elapsed >= maturing.maturationDurationMillis) 1.0
            else elapsed.toDouble() / maturing.maturationDurationMillis
        }
        if (progress == 1.0) return currentDamage

        val initial = maturing.initialDamageMultiplier
        val multiplier = initial / (1.0 + (initial - 1.0) * progress)
        return (currentDamage * multiplier).coerceAtMost(Double.MAX_VALUE)
    }
}
