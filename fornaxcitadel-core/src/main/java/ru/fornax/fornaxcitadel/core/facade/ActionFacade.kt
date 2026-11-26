package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionMeta
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.function.BooleanSupplier

class ActionFacade(
    private val actionManager: ActionManager
) {
    /** Executes synchronously, including from a processor. Handlers run after the outermost action succeeds. */
    fun execute(action: Action): ActionResult = actionManager.execute(action)

    /** Runs a trusted batch synchronously. See [ActionManager.executeBatch] for the commit contract. */
    fun executeBatch(actions: List<Action>, prepare: () -> Boolean, commit: () -> Unit): ActionResult =
        actionManager.executeBatch(actions, prepare, commit)

    fun isAllowed(action: Action): Boolean = actionManager.isAllowed(action)

    /**
     * Queues a non-empty batch on the owning game thread, including from an action handler.
     * The queue counts the whole batch as one entry and preserves the supplied action order.
     * At execution time, all actions must pass validation both before and after [prepare] returns true.
     * [commit] then applies the batch's changes once, followed by handler notifications for each action.
     */
    fun publish(actions: List<Action>, prepare: BooleanSupplier, commit: Runnable) {
        require(actions.isNotEmpty()) { "A batch must contain an action" }
        val snapshot = actions.toList()
        actionManager.post { actionManager.executeBatch(snapshot, prepare::getAsBoolean, commit::run) }
    }

    /**
     * Publishes on the owning game thread. Executes immediately when the manager is idle and
     * its queue is empty. Otherwise the action joins the end of the queue.
     */
    fun publish(action: Action) {
        if (actionManager.canStartExecution && !actionManager.hasPostedActions) {
            actionManager.execute(action)
        } else {
            actionManager.post(action)
        }
    }

    /** Registers a processor without changing the key's execution policy. */
    fun registerAction(processor: ActionProcessor<*>) = actionManager.registerProcessor(processor)

    /** Sets the policy for neutral validation unless the host already configured this key. */
    fun registerDefaultActionMeta(key: Key, meta: ActionMeta) = actionManager.registerActionMetaIfAbsent(key, meta)

    fun <T : Action> registerAction(processor: ActionProcessor<T>, meta: ActionMeta) {
        registerAction(processor)
        actionManager.registerActionMeta(processor.actionKey, meta)
    }

    fun <T : Action> registerValidator(validator: ActionValidator<T>) {
        actionManager.registerValidator(validator)
    }

    fun registerHandler(handler: ActionHandler<*>) = actionManager.registerHandler(handler)

    fun unregisterHandler(handler: ActionHandler<*>): Boolean = actionManager.unregisterHandler(handler)

    @JvmSynthetic
    internal fun drainPosted(limit: Int): Int = actionManager.drainPosted(limit)
}
