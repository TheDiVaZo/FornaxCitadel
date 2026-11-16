package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle

/** Read-only rule evaluated on the owning game thread. Return a finite, positive factor. */
fun interface MaturationMultiplier {
    fun getMultiplier(citadel: CitadelHandle): Double
}
