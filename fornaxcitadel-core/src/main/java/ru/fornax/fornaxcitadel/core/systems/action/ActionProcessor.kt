package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key

/**
 * Performs an authorized action. Finish checks before committing state or a child action.
 * ActionManager.executeChild is available only while running through the manager; after a child
 * succeeds this processor must also succeed. Unexpected failures do not roll back committed state.
 */
abstract class ActionProcessor<T: ActionData> {
    abstract val actionClass: Class<T>
    abstract val actionKey: Key

    abstract fun execute(action: T): ActionResult
}
