package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.MaturationDefinition
import ru.fornax.fornaxcitadel.modules.health.MaturationMultiplier
import ru.fornax.fornaxcitadel.modules.health.MaturationService

/** Maturation profiles, progress and duration multipliers for use on the owning game thread. */
class MaturationFacade internal constructor(private val service: MaturationService) {
    fun registerDefinition(key: Key, definition: MaturationDefinition) = service.registerDefinition(key, definition)

    fun findDefinition(key: Key): MaturationDefinition? = service.findDefinition(key)

    fun progress(citadel: ReadableHandle): Double = service.progress(citadel)

    fun isMature(citadel: ReadableHandle): Boolean = service.isMature(citadel)

    fun registerMultiplier(multiplier: MaturationMultiplier) = service.registerMultiplier(multiplier)
}
