package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key

/**
 * Routes actions by [Action.type] and executes processors synchronously on the calling thread.
 * Not thread-safe; use on the owning game thread, like the processors' component manager.
 */
class ProcessorCoordinator {
    private val processors = mutableMapOf<Key, ActionProcessor<*>>()

    /** Registers one processor per key. Registering the same instance again is a no-op. */
    fun registerProcessor(processor: ActionProcessor<*>) {
        val registered = processors[processor.actionKey]
        require(registered == null || registered === processor) {
            "Action key ${processor.actionKey} already has a registered processor"
        }
        processors[processor.actionKey] = processor
    }

    fun getProcessor(actionKey: Key): ActionProcessor<*>? = processors[actionKey]

    /**
     * Returns the processor's result, or false if no processor is registered for [action].
     * Throws [IllegalArgumentException] if the action's class does not match the processor's type.
     * Exceptions from the processor propagate to the caller.
     */
    fun execute(action: Action): Boolean {
        val processor = getProcessor(action.type) ?: return false
        return executeProcessor(processor, action)
    }

    private fun <T : Action> executeProcessor(processor: ActionProcessor<T>, action: Action): Boolean {
        require(processor.actionClass.isInstance(action)) {
            "Action ${action.javaClass.name} does not match ${processor.actionClass.name} for key ${action.type}"
        }
        return processor.execute(processor.actionClass.cast(action))
    }
}
