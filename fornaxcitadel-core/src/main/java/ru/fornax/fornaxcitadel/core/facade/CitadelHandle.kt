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
    fun <T : Component<*>> editComponent(componentClass: Class<T>, action: (T) -> Unit): Boolean =
        manager.editComponent(this, componentClass, action)

    fun <T : Component<*>> markDirty(componentClass: Class<T>, component: T): Boolean =
        manager.markDirty(this, componentClass, component)
}
