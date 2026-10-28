package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelEntity

/**
 * A particular loaded entity, not just a reusable address. Expires on removal or chunk reload.
 * Read/edit access must stay on the owning game thread; returned components are live instances.
 */
class CitadelHandle internal constructor(
    internal val manager: SimpleComponentManager,
    internal val chunk: ChunkCord,
    internal val entity: CitadelEntity,
    val position: CitadelPosition
) : ICitadelReference by position {
    val runtimeId: Int get() = entity.id
    val isCurrent: Boolean get() = manager.isCurrent(this)

    fun <T : Component> getComponent(componentClass: Class<T>): T? =
        manager.getComponent(this, componentClass)

    fun <T : Component> editComponent(componentClass: Class<T>, action: (T) -> Unit): Boolean =
        manager.editComponent(this, componentClass, action)

    fun <T : Component> markDirty(componentClass: Class<T>, component: T): Boolean =
        manager.markDirty(this, componentClass, component)
}
