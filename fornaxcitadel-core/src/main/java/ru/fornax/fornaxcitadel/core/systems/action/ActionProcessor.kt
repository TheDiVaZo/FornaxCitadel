package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.util.Key

/**
 * Performs an authorized action. Finish checks before committing state.
 * Use [ActionFacade.execute] or [ActionFacade.executeBatch] for synchronous child operations.
 * The processor decides how each child result affects its own result. Committed changes survive
 * a later denial or exception. Use [ActionFacade.publish] to queue a subsequent action.
 */
abstract class ActionProcessor<T: Action> {
    abstract val actionClass: Class<T>
    abstract val actionKey: Key

    abstract fun execute(action: T): ActionResult
}
