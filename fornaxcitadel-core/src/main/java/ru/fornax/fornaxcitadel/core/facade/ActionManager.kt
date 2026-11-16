package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionMetaRegistry
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.HandlerCoordinator
import ru.fornax.fornaxcitadel.core.systems.action.ProcessorCoordinator
import ru.fornax.fornaxcitadel.core.systems.action.ValidatorCoordinator
import java.util.ArrayDeque

/**
 * Validates and executes actions synchronously on the calling thread.
 * Not thread-safe; use on the owning game thread, like [componentManager].
 */
class ActionManager @JvmOverloads constructor(
    val componentManager: ComponentManager,
    val processorCoordinator: ProcessorCoordinator = ProcessorCoordinator(),
    val validatorCoordinator: ValidatorCoordinator = ValidatorCoordinator(),
    val citadels: CitadelFacade = CitadelFacade(componentManager),
    val actionMetaRegistry: ActionMetaRegistry = ActionMetaRegistry(ActionMetaRegistry.ActionMeta.DEFAULT),
    val handlerCoordinator: HandlerCoordinator = HandlerCoordinator()
) {

    init {
        require(citadels.componentManager === componentManager) { "Actions and citadel facade must share their storage" }
    }

    private var executing = false
    private val posted = ArrayDeque<ActionData>()

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
     * prepare must not mutate gameplay state; commit must perform no further cancelable validation.
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
     * A Success authorizes execution; an aggregate Neutral uses [actionMetaRegistry].
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
