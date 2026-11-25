package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionMeta
import ru.fornax.fornaxcitadel.core.systems.action.ActionMetaRegistry
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.HandlerCoordinator
import ru.fornax.fornaxcitadel.core.systems.action.ProcessorCoordinator
import ru.fornax.fornaxcitadel.core.systems.action.ValidatorCoordinator
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.ArrayDeque

/**
 * Validates and executes actions synchronously on the owning game thread.
 */
class ActionManager {
    private val processorCoordinator = ProcessorCoordinator()
    private val validatorCoordinator = ValidatorCoordinator()
    private val handlerCoordinator = HandlerCoordinator()
    private val actionMetaRegistry = ActionMetaRegistry(ActionMeta.DEFAULT)
    private var executing = false
    private val posted = ArrayDeque<ActionData>()

    /** Registers one processor per action key. Registering the same instance again is a no-op. */
    fun registerProcessor(processor: ActionProcessor<*>) = processorCoordinator.registerProcessor(processor)

    fun unregisterProcessor(processor: ActionProcessor<*>): Boolean = processorCoordinator.unregisterProcessor(processor)

    fun registerValidator(validator: ActionValidator<*>) = validatorCoordinator.registerValidator(validator)

    fun unregisterValidator(validator: ActionValidator<*>): Boolean = validatorCoordinator.unregisterValidator(validator)

    fun registerHandler(handler: ActionHandler<*>) = handlerCoordinator.registerHandler(handler)

    fun unregisterHandler(handler: ActionHandler<*>): Boolean = handlerCoordinator.unregisterHandler(handler)

    /** Sets the execution policy for [key] when all applicable validators are neutral. */
    fun registerActionMeta(key: Key, meta: ActionMeta) = actionMetaRegistry.register(key, meta)

    @JvmSynthetic
    internal fun registerActionMetaIfAbsent(key: Key, meta: ActionMeta): Boolean =
        actionMetaRegistry.registerIfAbsent(key, meta)

    @JvmSynthetic
    internal fun unregisterActionMeta(key: Key, meta: ActionMeta): Boolean =
        actionMetaRegistry.unregister(key, meta)

    /** Read-only authorization using the same metadata as execute. Does not notify handlers. */
    fun isAllowed(action: ActionData): Boolean = runRoot { validate(action) }

    private fun validate(action: ActionData): Boolean = when (validatorCoordinator.validate(action)) {
        ActionValidator.Result.Rejected -> false
        ActionValidator.Result.Success -> true
        ActionValidator.Result.Neutral -> actionMetaRegistry.getMetaOrDefault(action.type).executeIfNeutralActionResult
    }

    /**
     * Trusted platform operation, e.g. all reinforced parts of one physical construction.
     * Validate every intent before and after prepare, then commit exactly once.
     * prepare does not commit the requested changes, but may dispatch events whose listeners change
     * gameplay state; intents are validated again afterward. commit performs no further cancelable validation.
     * This is not a world transaction: unexpected commit failures propagate without simulated rollback.
     * Notifications run only after the entire commit succeeds, under the normal reentrancy guard.
     */
    fun executeBatch(actions: List<ActionData>, prepare: () -> Boolean, commit: () -> Unit): ActionResult = runRoot {
        require(actions.isNotEmpty()) { "A batch must contain an action" }
        val snapshot = actions.toList()
        if (!snapshot.all(::validate) || !prepare() || !snapshot.all(::validate)) return@runRoot ActionResult.DENIED
        commit()
        snapshot.forEach(handlerCoordinator::handle)
        ActionResult.SUCCESS
    }

    /**
     * Returns DENIED without executing the processor if any validator rejects the action.
     * A Success authorizes execution. An aggregate Neutral uses the action's configured metadata.
     * Allowed execution returns the processor's result (or DENIED if none is registered).
     * Validators must leave gameplay state unchanged. Validator and processor exceptions propagate.
     * On SUCCESS, handlers are notified after the processor finishes.
     * Matching handlers run in registration order; registration changes apply afterward.
     * Handler exceptions are logged without changing the result or skipping later handlers.
     * Handlers observe completed actions; modifying citadels from a handler has undefined behavior
     * and is not checked at runtime. Use [post] to request subsequent changes.
     * Nested execution is rejected through the last handler.
     */
    fun execute(action: ActionData): ActionResult = runRoot {
        if (!validate(action)) return@runRoot ActionResult.DENIED
        val result = processorCoordinator.execute(action)
        if (result == ActionResult.SUCCESS) handlerCoordinator.handle(action)
        result
    }

    private inline fun <T> runRoot(operation: () -> T): T {
        check(!executing && !handlerCoordinator.isHandling) {
            "Nested action execution is not allowed; post the action instead"
        }
        executing = true
        return try {
            operation()
        } finally {
            executing = false
        }
    }

    /** Queues intent only, without claiming success. Call on the owning game thread. */
    fun post(action: ActionData) { posted.addLast(action) }

    /** Discards pending intents when the owning module stops, without executing them. */
    fun clearPosted() { posted.clear() }

    /**
     * Runs at most [limit] queued actions on the owning thread. The module's queue service
     * calls this on subsequent ticks while the module is enabled.
     * Returns the number attempted. Failures propagate; remaining actions stay queued.
     * Callers needing each result should use execute directly outside handler callbacks.
     */
    @JvmOverloads
    fun drainPosted(limit: Int = 1024): Int {
        require(limit >= 0) { "Action limit must be non-negative" }
        check(!executing && !handlerCoordinator.isHandling) { "Cannot drain actions during another action or handling" }
        var count = 0
        while (count < limit && posted.isNotEmpty()) {
            val action = posted.removeFirst()
            count++
            execute(action)
        }
        return count
    }
}
