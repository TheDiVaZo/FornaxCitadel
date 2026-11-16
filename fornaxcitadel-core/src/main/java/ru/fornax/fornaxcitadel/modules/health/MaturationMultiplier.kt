package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.facade.ReadableHandle

/**
 * Read-only rule evaluated on the owning game thread. Return a finite, positive factor.
 * Do not mutate gameplay state or live definition settings, including through captured references,
 * other handles, or services. Rule registration changes retain the current calculation's snapshot.
 */
fun interface MaturationMultiplier {
    fun getMultiplier(citadel: ReadableHandle): Double
}
