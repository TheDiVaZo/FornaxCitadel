package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageModifier
import ru.fornax.fornaxcitadel.modules.health.HealthDefinition
import ru.fornax.fornaxcitadel.modules.health.HealthService

/** Health profiles, queries and ordinary damage modifiers for use on the owning game thread. */
class HealthFacade internal constructor(private val service: HealthService) {
    fun registerDefinition(key: Key, definition: HealthDefinition) = service.registerDefinition(key, definition)

    fun findDefinition(key: Key): HealthDefinition? = service.findDefinition(key)

    fun isInvulnerable(key: Key): Boolean = service.isInvulnerable(key)

    fun isInvulnerable(citadel: ReadableHandle): Boolean = service.isInvulnerable(citadel)

    fun maxHealth(citadel: ReadableHandle): Double? = service.maxHealth(citadel)

    fun currentHealth(citadel: ReadableHandle): Double? = service.currentHealth(citadel)

    fun registerModifier(modifier: DamageModifier) = service.registerModifier(modifier)
}
