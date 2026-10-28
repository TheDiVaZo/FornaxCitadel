package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import java.util.UUID

/**
 * Reusable cursor for component access by global block coordinates.
 * Do not retain it across nested operations. Prefer [CitadelHandle] when entity identity matters.
 * Delegates to [manager]; its threading and live component lifetime rules apply.
 */
class CitadelReference(
    val manager: SimpleComponentManager,
    override var world: UUID,
    override var x: Int,
    override var y: Int,
    override var z: Int,
): ICitadelReference {
    constructor(manager: SimpleComponentManager) : this(manager, UUID(0, 0), 0,0,0)

    fun loadOf(action: ActionData) {
        this.world = action.citadelW
        this.x = action.citadelX
        this.y = action.citadelY
        this.z = action.citadelZ
    }

    fun loadOf(world: UUID, x: Int, y: Int, z: Int): CitadelReference {
        this.world = world
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    /** Returns the live component, or null if the chunk, position, or component is absent. */
    fun <T : Component> getComponent(componentClass: Class<T>): T? {
        return manager.getComponent(componentClass, world, x, y, z)
    }

    /** Inserts a live component and returns the previous one. The chunk and position must exist. */
    fun <T : Component> putComponent(componentClass: Class<T>, component: T): T? {
        return manager.putComponent(componentClass, world, x, y, z, component)
    }

    /** Removes this component type and returns the removed live instance, if any. */
    fun <T : Component> removeComponent(componentClass: Class<T>): T? {
        return manager.removeComponent(componentClass, world, x, y, z)
    }

    /** Removes this position and all its components; false if absent or unloaded. */
    fun removeAllComponents(): Boolean {
        return manager.removeAllComponents(world, x, y, z)
    }

    /** Edits the live component and marks it dirty even if [action] throws; false if absent. */
    fun <T : Component> editComponent(componentClass: Class<T>, action: (T) -> Unit): Boolean {
        return manager.editComponent(componentClass, world, x, y, z, action)
    }

    /** Marks a directly mutated component dirty; false if it is no longer stored here. */
    fun <T : Component> markDirty(componentClass: Class<T>, component: T): Boolean {
        return manager.markDirty(componentClass, world, x, y, z, component)
    }
}
