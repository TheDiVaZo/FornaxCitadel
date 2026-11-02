package ru.fornax.fornaxcitadel.core.systems.observation

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle

/**
 * Processes observed citadels containing all [requiredComponents]. Systems may share requirements.
 * Intended primarily for presentation, but gameplay operations are allowed through the usual
 * core APIs. Calls depend on viewers being present; their count is not a measure of game time.
 * Components are live and owner-thread confined: use editComponent/markDirty for mutations.
 * Keep transient display resources in the system, not in persistent components.
 * Registration changes are applied after the current dispatch. If callbacks repeatedly replace
 * their required components or membership, re-entering the same target/viewer is deferred to a later pass.
 */
interface ObservationSystem<V> {
    /**
     * All of these component types must be present. An empty set accepts any live observed citadel.
     * Register the types with the component manager before observing them. The service copies this
     * set on system registration; unregister and register again to change the requirements.
     */
    val requiredComponents: Set<Class<out Component>>

    fun onViewerEnter(citadel: CitadelHandle, viewer: V) {}

    /**
     * Once per matching active citadel per scheduled pass, with a stable, unmodifiable audience.
     * Requirements are checked before the call; read needed components through [CitadelHandle].
     */
    fun tick(citadel: CitadelHandle, viewers: List<V>)

    /** Releases this viewer's resources without requiring an available component or live handle. */
    fun onViewerLeave(citadel: ObservationTarget, viewer: V) {}

    /**
     * Releases this system's citadel session after all its viewers have left. Also called when
     * any required component is removed or replaced, on system unregistration and service shutdown.
     * Changing fields of the same component instance or changing optional components does not
     * restart the session. Replacement is determined by instance identity, not component equality.
     */
    fun onDetach(citadel: ObservationTarget) {}
}
