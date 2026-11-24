package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.ReadableComponent

/**
 * Read access to a particular loaded entity, not just a reusable address.
 * Expires on removal or chunk reload. Position and runtime ID remain available after expiry.
 * Access stays on the owning game thread; components are live instances exposed through read-only types.
 * Queries leave gameplay state unchanged. A consumer's ability to request actions is defined separately
 * by its callback contract, such as an observation system or an event listener.
 */
abstract class ReadableHandle internal constructor(
    @get:JvmSynthetic
    protected val manager: ComponentManager,
    val position: CitadelPosition,
    val runtimeId: Int
) : ICitadelReference by position {
    val isCurrent: Boolean get() = manager.isCurrent(this)

    @JvmSynthetic
    internal fun belongsTo(candidate: ComponentManager): Boolean = manager === candidate

    /** Returns current component state without refreshing, mutating, or marking it dirty. */
    fun <R : ReadableComponent> getComponent(componentClass: Class<out Component<out R>>): R? =
        manager.getComponent(this, componentClass)
}
