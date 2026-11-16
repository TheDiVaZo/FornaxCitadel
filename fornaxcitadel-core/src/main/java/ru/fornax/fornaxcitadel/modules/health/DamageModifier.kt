package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.facade.ReadableHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

/**
 * Transforms authorized damage on the owning game thread, in registration order.
 * Do not mutate gameplay state, permissions, or live definition settings, including through
 * captured references, other handles, or services. Do not retain the live citadel or its components.
 * Return finite, non-negative damage. Modifier registration changes keep the service's snapshot semantics.
 * HealthService finishes the complete calculation before editing health.
 * Health may still be absent before the first hit; use HealthService.currentHealth for effective HP.
 */
fun interface DamageModifier {
    fun getDamage(context: ActionContext, citadel: ReadableHandle, currentDamage: Double): Double
}
