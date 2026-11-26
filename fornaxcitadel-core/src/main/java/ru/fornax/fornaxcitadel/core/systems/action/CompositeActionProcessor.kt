package ru.fornax.fornaxcitadel.core.systems.action

/** Performs an authorized action that synchronously validates or commits child intents. */
abstract class CompositeActionProcessor<T : Action> : ActionProcessor<T>() {
    final override fun execute(action: T): ActionResult =
        error("A composite action must be executed by the action manager")

    abstract fun execute(action: T, scope: ActionExecutionScope): ActionResult
}

/**
 * Available on the owning thread during the parent processor's execution. Do not retain this
 * scope or pass it to external callbacks. Its operations cannot be nested inside each other.
 */
interface ActionExecutionScope {
    fun isAllowed(action: Action): Boolean

    /** Validates every child before and after prepare; on success commits once, then notifies child handlers. */
    fun executeBatch(actions: List<Action>, prepare: () -> Boolean, commit: () -> Unit): ActionResult
}
