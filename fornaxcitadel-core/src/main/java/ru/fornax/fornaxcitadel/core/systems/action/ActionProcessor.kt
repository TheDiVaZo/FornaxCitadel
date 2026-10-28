package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.util.Key

abstract class ActionProcessor<T: Action>(protected val manager: SimpleComponentManager) {
    abstract val actionClass: Class<T>
    abstract val actionKey: Key

    abstract fun execute(action: T): Boolean
}