package ru.fornax.fornaxcitadel.modules.breaking

import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor

class BreakProcessor(private val service: BreakService) : ActionProcessor<BreakAction>() {
    override val actionClass = BreakAction::class.java
    override val actionKey = BreakAction.KEY
    override fun execute(action: BreakAction) = service.breakCitadel(action)
}
