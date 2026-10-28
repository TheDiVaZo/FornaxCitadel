package ru.fornax.fornaxcitadel.modules.remove

import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor

class RemoveProcessor(private val service: RemoveService) : ActionProcessor<RemoveAction>() {
    override val actionClass = RemoveAction::class.java
    override val actionKey = RemoveAction.KEY
    override fun execute(action: RemoveAction) = service.request(action)
}
