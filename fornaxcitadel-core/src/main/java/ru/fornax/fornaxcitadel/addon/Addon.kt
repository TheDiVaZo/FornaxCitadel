package ru.fornax.fornaxcitadel.addon

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.util.Key

/** Declares a plugin's persisted component types for citadel restoration. */
interface Addon {
    val key: Key

    /**
     * FornaxCitadel reads these declarations on the game thread before restoring citadels.
     * They must be ready when the plugin's `onLoad()` returns.
     */
    fun defaultComponents(): List<ComponentRegistration> = emptyList()
}

/** The stable storage key and the component class whose no-argument constructor is used on restore. */
data class ComponentRegistration(val key: Key, val componentClass: Class<out Component<*>>)
