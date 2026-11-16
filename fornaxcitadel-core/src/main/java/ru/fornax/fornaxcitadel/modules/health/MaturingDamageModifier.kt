package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

/** Amplifies damage to reproduce a linearly growing effective maximum without changing stored HP. */
class MaturingDamageModifier(private val maturation: MaturationService) : DamageModifier {
    override fun getDamage(context: ActionContext, citadel: CitadelHandle, currentDamage: Double): Double {
        require(currentDamage.isFinite() && currentDamage >= 0.0) {
            "Damage must be finite and non-negative"
        }
        if (citadel.getComponent(Maturing::class.java) == null) return currentDamage
        if (currentDamage == 0.0) return currentDamage

        val definition = maturation.definition(citadel) ?: return currentDamage
        val progress = maturation.progress(citadel, definition)
        if (progress == 1.0) return currentDamage

        val initial = definition.initialDamageMultiplier
        val multiplier = initial / (1.0 + (initial - 1.0) * progress)
        return (currentDamage * multiplier).coerceAtMost(Double.MAX_VALUE)
    }
}
