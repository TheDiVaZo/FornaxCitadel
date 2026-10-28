package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ProcessorCoordinator
import ru.fornax.fornaxcitadel.core.systems.action.ValidatorCoordinator
import java.util.ArrayDeque

/**
 * Validates and executes actions synchronously on the calling thread.
 * Not thread-safe; use on the owning game thread, like [componentManager].
 */
class ActionManager(
    val componentManager: SimpleComponentManager,
    val processorCoordinator: ProcessorCoordinator = ProcessorCoordinator(),
    val validatorCoordinator: ValidatorCoordinator = ValidatorCoordinator()
) {
    private var executing = false
    private val posted = ArrayDeque<ActionData>()

    /**
     * Returns DENIED without executing the processor if validation rejects the action.
     * Successful or neutral validation allows execution, returning the processor's result
     * (or DENIED if none is registered). Exceptions propagate to the caller.
     * Nested execution is rejected: observers can [post] work for [drainPosted] instead.
     */
    fun execute(action: ActionData): ActionResult {
        check(!executing) { "Nested action execution is not allowed; post the action instead" }
        executing = true
        try {
            return when (validatorCoordinator.validate(action)) {
                ActionValidator.Result.Rejected -> ActionResult.DENIED
                ActionValidator.Result.Success, ActionValidator.Result.Neutral -> processorCoordinator.execute(action)
            }
        } finally {
            executing = false
        }
    }

    /** Queues intent only, without claiming success. Call on the owning game thread. */
    fun post(action: ActionData) { posted.addLast(action) }

    /**
     * Runs at most [limit] queued actions on the owning thread, normally later in the same tick.
     * Returns the number attempted. Failures propagate; remaining actions stay queued.
     * Callers needing each result should use execute directly outside observer callbacks.
     */
    @JvmOverloads
    fun drainPosted(limit: Int = 1024): Int {
        require(limit >= 0) { "Action limit must be non-negative" }
        check(!executing) { "Cannot drain actions during another action" }
        var count = 0
        while (count < limit && posted.isNotEmpty()) {
            val action = posted.removeFirst()
            count++
            execute(action)
        }
        return count
    }
}
