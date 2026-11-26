package ru.fornax.fornaxcitadel.addon

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.facade.HealthFacade
import ru.fornax.fornaxcitadel.core.facade.MaturationFacade
import ru.fornax.fornaxcitadel.core.util.Key

/** Declares a plugin's component and citadel types before chunk restoration. */
interface Addon {
    val key: Key

    /**
     * FornaxCitadel reads these declarations on the game thread before restoring citadels.
     * They must be ready when the plugin's `onLoad()` returns.
     */
    fun defaultComponents(): List<ComponentRegistration> = emptyList()

    /** Registers citadel types and their health and maturation profiles before chunks are restored. */
    fun registerDefinitions(definitions: CitadelDefinitionRegistry, health: HealthFacade, maturation: MaturationFacade) {}
}

/** The stable storage key and the component class whose no-argument constructor is used on restore. */
data class ComponentRegistration(val key: Key, val componentClass: Class<out Component<*>>)
