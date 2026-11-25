package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.DeferredRegistrationList
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Dispatches action notifications synchronously on the calling thread, in registration order.
 * Not thread-safe; access on the owning game thread, like [ValidatorCoordinator].
 */
class HandlerCoordinator {
    private val handlers = DeferredRegistrationList<ActionHandler<*>>()

    @get:JvmSynthetic
    internal val isHandling: Boolean get() = handlers.isIterating

    /** Multiple handlers can share an action class. Registering the same instance is a no-op. */
    fun registerHandler(handler: ActionHandler<*>) {
        handlers.register(handler)
    }

    /** Removes this instance, accounting for registration changes already queued by callbacks. */
    fun unregisterHandler(handler: ActionHandler<*>): Boolean = handlers.unregister(handler)

    /**
     * Notifies handlers whose action class accepts [action], including handlers for base classes.
     * This is a manual notification: it neither executes the action nor verifies its result.
     * ActionManager calls it only after a processor returns [ActionResult.SUCCESS].
     * Handlers must observe citadel state as described by [ActionHandler]; changing citadels here
     * has undefined behavior and is not checked at runtime. Post actions for subsequent changes.
     * Handler exceptions are logged without skipping later handlers. Registration changes are
     * applied in order when dispatch finishes, including after a failure. The active list remains
     * unchanged during traversal. Nested handling on this coordinator is rejected.
     */
    fun handle(action: Action) {
        val current = handlers.beginIteration()
        try {
            var index = 0
            while (index < current.size) {
                try {
                    handleAction(current[index++], action)
                } catch (failure: Exception) {
                    reportHandlerFailure(action, failure)
                }
            }
        } finally {
            handlers.endIteration()
        }
    }

    private fun <T : Action> handleAction(handler: ActionHandler<T>, action: Action) {
        val actionClass = handler.actionClass
        if (actionClass.isInstance(action)) handler.handle(actionClass.cast(action))
    }

    private fun reportHandlerFailure(action: Action, failure: Exception) {
        try {
            LOG.log(Level.WARNING, "Action handler failed for ${action.type}", failure)
        } catch (_: Exception) {
            // Logging failures cannot undo a successful action or skip its remaining handlers.
        }
    }

    private companion object {
        val LOG: Logger = Logger.getLogger(HandlerCoordinator::class.java.name)
    }
}
