package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.systems.action.Action
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
 * Validates and executes actions synchronously. Call from the owning game thread.
 */
class ActionManager {
    private enum class Phase { IDLE, PROCESSING, DISPATCHING }

    private val processorCoordinator = ProcessorCoordinator()
    private val validatorCoordinator = ValidatorCoordinator()
    private val handlerCoordinator = HandlerCoordinator()
    private val actionMetaRegistry = ActionMetaRegistry(ActionMeta.DEFAULT)
    private var phase = Phase.IDLE
    private val completed = ArrayList<Action>()
    private val posted = ArrayDeque<() -> Unit>()

    @get:JvmSynthetic
    internal val canStartExecution: Boolean get() = phase == Phase.IDLE

    @get:JvmSynthetic
    internal val hasPostedActions: Boolean get() = posted.isNotEmpty()

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
    fun isAllowed(action: Action): Boolean = dispatch { validate(action) }

    private fun validate(action: Action): Boolean = when (validatorCoordinator.validate(action)) {
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
     * A successful commit records its actions in batch order for notification when the root succeeds.
     */
    fun executeBatch(actions: List<Action>, prepare: () -> Boolean, commit: () -> Unit): ActionResult = dispatch {
        require(actions.isNotEmpty()) { "A batch must contain an action" }
        val snapshot = actions.toList()
        if (!snapshot.all(::validate) || !prepare() || !snapshot.all(::validate)) return@dispatch ActionResult.DENIED
        commit()
        completed.addAll(snapshot)
        ActionResult.SUCCESS
    }

    /**
     * Returns DENIED without executing the processor if any validator rejects the action.
     * A Success authorizes execution. An aggregate Neutral uses the action's configured metadata.
     * Allowed execution returns the processor's result (or DENIED if none is registered).
     * Validators must leave gameplay state unchanged. Validator and processor exceptions propagate.
     * A processor can synchronously execute children and decide its own result. After the root
     * succeeds, handlers receive successful descendants before their parent. A denied or failed
     * branch discards its notifications. Changes already committed by that branch remain.
     * Matching handlers run in registration order; registration changes apply afterward.
     * Handler exceptions are logged without changing the result or skipping later handlers.
     * Handlers observe completed actions; modifying citadels from a handler has undefined behavior
     * and is not checked at runtime. Use [post] to request subsequent changes.
     * Validators, batch callbacks and handlers can publish follow-ups for later execution.
     */
    fun execute(action: Action): ActionResult = dispatch {
        if (!validate(action)) return@dispatch ActionResult.DENIED
        val branchStart = completed.size
        var result = ActionResult.DENIED
        phase = Phase.PROCESSING
        try {
            result = processorCoordinator.execute(action)
        } finally {
            phase = Phase.DISPATCHING
            if (result != ActionResult.SUCCESS && completed.size > branchStart) {
                completed.subList(branchStart, completed.size).clear()
            }
        }
        if (result == ActionResult.SUCCESS) completed.add(action)
        result
    }

    private inline fun <T> dispatch(operation: () -> T): T {
        val previous = phase
        check(previous != Phase.DISPATCHING) {
            "Only action processors can execute nested actions; post the action instead"
        }
        val root = previous == Phase.IDLE
        phase = Phase.DISPATCHING
        try {
            val result = operation()
            if (root) completed.forEach(handlerCoordinator::handle)
            return result
        } finally {
            if (root) completed.clear()
            phase = previous
        }
    }

    /** Queues intent only, without claiming success. Call on the owning game thread. */
    fun post(action: Action) = post { execute(action) }

    @JvmSynthetic
    internal fun post(operation: () -> Unit) { posted.addLast(operation) }

    /** Discards pending intents during shutdown. */
    fun clearPosted() { posted.clear() }

    /**
     * Runs at most [limit] queued entries on the owning thread. A single action or a batch
     * published through [ActionFacade.publish] counts as one entry. The queue service
     * calls this on each tick while active.
     * Returns the number attempted. Failures propagate; remaining actions stay queued.
     * Callers needing each result should use execute directly outside handler callbacks.
     */
    @JvmOverloads
    fun drainPosted(limit: Int = 1024): Int {
        require(limit >= 0) { "Action limit must be non-negative" }
        check(canStartExecution) { "Cannot drain actions during another action or handling" }
        var count = 0
        while (count < limit && posted.isNotEmpty()) {
            val operation = posted.removeFirst()
            count++
            operation()
        }
        return count
    }
}
