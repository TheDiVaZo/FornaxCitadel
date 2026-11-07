package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

/**
 * Transforms authorized damage on the owning game thread, in registration order.
 * Do not mutate or retain the live citadel or its components. Return finite, non-negative damage.
 * HealthService finishes the complete calculation before editing health.
 */
fun interface DamageModifier {
    fun getDamage(context: ActionContext, citadel: CitadelHandle, currentDamage: Double): Double
}
