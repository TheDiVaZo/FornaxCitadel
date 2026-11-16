package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.facade.ReadableHandle

/** Read-only rule evaluated on the owning game thread. Return a finite, positive factor. */
fun interface MaturationMultiplier {
    fun getMultiplier(citadel: ReadableHandle): Double
}
