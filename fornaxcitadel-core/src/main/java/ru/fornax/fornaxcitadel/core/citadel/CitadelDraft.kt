package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.facade.ICitadelReference

/** Components being prepared for one new citadel, not yet visible in storage. */
interface CitadelDraft : ICitadelReference {
    val position: CitadelPosition
    override val world get() = position.world
    override val x get() = position.x
    override val y get() = position.y
    override val z get() = position.z

    fun <T : Component> getComponent(componentClass: Class<T>): T?
    fun <T : Component> addComponent(componentClass: Class<T>, component: T)
}

/**
 * Preserves initialization order and rejects duplicate component types. A successful commit
 * consumes this draft; instances transferred to storage must then follow the live access rules.
 */
class MutableCitadelDraft(override val position: CitadelPosition) : CitadelDraft {
    private val components = LinkedHashMap<Class<out Component>, Component>()
    private var committed = false

    override fun <T : Component> getComponent(componentClass: Class<T>): T? {
        checkOpen()
        return components[componentClass]?.let(componentClass::cast)
    }

    override fun <T : Component> addComponent(componentClass: Class<T>, component: T) {
        checkOpen()
        require(componentClass.isInstance(component)) {
            "Component ${component.javaClass.name} does not match ${componentClass.name}"
        }
        require(!components.containsKey(componentClass)) {
            "Component ${componentClass.name} is already present in the draft"
        }
        components[componentClass] = component
    }

    internal fun componentsForCommit(): List<Pair<Class<out Component>, Component>> {
        checkOpen()
        return components.entries.map { it.key to it.value }
    }

    internal fun markCommitted() {
        checkOpen()
        committed = true
        components.clear()
    }

    private fun checkOpen() {
        check(!committed) { "Citadel draft has already been committed" }
    }
}
