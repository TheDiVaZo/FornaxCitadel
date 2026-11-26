package ru.fornax.fornaxcitadel.addon

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.HealthService

/** Declares a plugin's component and citadel types before chunk restoration. */
interface Addon {
    val key: Key

    /**
     * FornaxCitadel reads these declarations on the game thread before restoring citadels.
     * They must be ready when the plugin's `onLoad()` returns.
     */
    fun defaultComponents(): List<ComponentRegistration> = emptyList()

    /** Registers this addon's types and health profiles on the game thread before chunks are restored. */
    fun registerDefinitions(definitions: CitadelDefinitionRegistry, health: HealthService) {}
}

/** The stable storage key and the component class whose no-argument constructor is used on restore. */
data class ComponentRegistration(val key: Key, val componentClass: Class<out Component<*>>)
