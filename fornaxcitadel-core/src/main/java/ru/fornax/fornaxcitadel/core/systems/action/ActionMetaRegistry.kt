package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key

/** Per-action execution defaults, configured on the owning game thread. */
class ActionMetaRegistry(
    var defaultMeta: ActionMeta
) {

    private val actionsMeta: MutableMap<Key, ActionMeta> = HashMap()

    fun unregister(type: Key) {
        actionsMeta.remove(type)
    }

    fun register(type: Key, actionMeta: ActionMeta) {
        actionsMeta[type] = actionMeta
    }

    fun getMeta(type: Key): ActionMeta? = actionsMeta[type]

    fun getMetaOrDefault(type: Key): ActionMeta {
        return actionsMeta[type] ?: defaultMeta
    }
}
