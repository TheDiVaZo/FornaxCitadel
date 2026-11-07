package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.component.Component

/**
 * A particular loaded entity, not just a reusable address. Expires on removal or chunk reload.
 * Stores only its manager, position and runtime identity, without directly retaining the chunk or entity.
 * Position and runtime ID remain available after expiry; live access resolves through the manager.
 * Read/edit access must stay on the owning game thread; returned components are live instances.
 */
class CitadelHandle internal constructor(
    internal val manager: ComponentManager,
    val position: CitadelPosition,
    val runtimeId: Int
) : ICitadelReference by position {
    val isCurrent: Boolean get() = manager.isCurrent(this)

    /** Returns current component state without refreshing, mutating, or marking it dirty. */
    fun <T : Component> getComponent(componentClass: Class<T>): T? =
        manager.getComponent(this, componentClass)

    fun <T : Component> editComponent(componentClass: Class<T>, action: (T) -> Unit): Boolean =
        manager.editComponent(this, componentClass, action)

    fun <T : Component> markDirty(componentClass: Class<T>, component: T): Boolean =
        manager.markDirty(this, componentClass, component)
}
