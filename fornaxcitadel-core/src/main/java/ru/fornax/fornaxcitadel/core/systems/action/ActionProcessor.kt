package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.util.Key

/**
 * Performs an authorized action. Finish checks before committing state.
 * Previously committed changes survive unexpected failures. Use [ActionFacade.publish] to queue
 * a subsequent action. Use [CompositeActionProcessor] for a synchronous batch of child actions.
 */
abstract class ActionProcessor<T: Action> {
    abstract val actionClass: Class<T>
    abstract val actionKey: Key

    abstract fun execute(action: T): ActionResult
}
