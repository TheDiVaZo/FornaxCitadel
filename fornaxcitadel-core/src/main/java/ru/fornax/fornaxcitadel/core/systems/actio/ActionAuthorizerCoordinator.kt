package ru.fornax.fornaxcitadel.core.systems.actio

import ru.fornax.fornaxcitadel.core.util.DeferredRegistrationList

class ActionAuthorizerCoordinator<S> {
    private val authorizers = DeferredRegistrationList<ActionAuthorizer<S, out Action>>()

    fun register(actionAuthorizer: ActionAuthorizer<S, *>) = authorizers.register(actionAuthorizer)

    fun unregister(actionAuthorizer: ActionAuthorizer<S, *>): Boolean = authorizers.unregister(actionAuthorizer)

    fun testAuthorize(source: S, action: Action): Boolean {
        val current = authorizers.beginIteration()
        try {
            return current.any { authorizeAction(it, source, action) }
        } finally {
            authorizers.endIteration()
        }
    }

    private fun authorizeAction(actionAuthorizer: ActionAuthorizer<S, *>, source: S, action: Action): Boolean {
        return actionAuthorizer.actionClass.isInstance(action) && actionAuthorizer.allows(source, actionAuthorizer.actionClass.cast(action))
    }
}
