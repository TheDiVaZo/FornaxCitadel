package ru.fornax.fornaxcitadel.modules.place

import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor

class PlaceProcessor(private val service: PlaceService) : ActionProcessor<PlaceAction>() {
    override val actionClass = PlaceAction::class.java
    override val actionKey = PlaceAction.KEY
    override fun execute(action: PlaceAction) = service.place(action)
}
