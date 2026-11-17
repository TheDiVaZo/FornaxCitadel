package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.component.Component

/**
 * Adds trusted write access to a loaded entity. Reads use the same read-only types as [ReadableHandle].
 * Edits must stay on the owning game thread and finish before the component is replaced or unloaded.
 */
class CitadelHandle internal constructor(
    manager: ComponentManager,
    position: CitadelPosition,
    runtimeId: Int
) : ReadableHandle(manager, position, runtimeId) {
    /** Inserts a component and returns the previous instance. Throws if this handle has expired. */
    fun <T : Component<*>> putComponent(componentClass: Class<T>, component: T): T? =
        manager.putComponent(this, componentClass, component)

    /** Removes decoded or opaque data of this type; an expired handle leaves replacements untouched. */
    fun <T : Component<*>> removeComponent(componentClass: Class<T>): T? =
        manager.removeComponent(this, componentClass)

    /** Marks even partial changes dirty if the callback throws; false for an absent component or expired handle. */
    fun <T : Component<*>> editComponent(componentClass: Class<T>, action: (T) -> Unit): Boolean =
        manager.editComponent(this, componentClass, action)

    /** Marks a retained mutable instance dirty only while it is still the current component. */
    fun <T : Component<*>> markDirty(componentClass: Class<T>, component: T): Boolean =
        manager.markDirty(this, componentClass, component)
}
