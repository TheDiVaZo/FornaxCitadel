package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key

abstract class ActionProcessor<T: ActionData> {
    abstract val actionClass: Class<T>
    abstract val actionKey: Key

    abstract fun execute(action: T): ActionResult
}
