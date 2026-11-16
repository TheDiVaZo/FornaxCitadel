package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key

/**
 * Performs an authorized action. Finish checks before committing state.
 * Unexpected failures do not roll back committed state. Use ActionManager.post for subsequent actions;
 * nested execution through the manager is not allowed.
 */
abstract class ActionProcessor<T: ActionData> {
    abstract val actionClass: Class<T>
    abstract val actionKey: Key

    abstract fun execute(action: T): ActionResult
}
