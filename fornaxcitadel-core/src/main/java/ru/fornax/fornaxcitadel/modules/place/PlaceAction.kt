package ru.fornax.fornaxcitadel.modules.place

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.util.Key

class PlaceAction @JvmOverloads constructor(
    context: ActionContext,
    position: CitadelPosition,
    val definitionKey: Key,
    val reinforcementItemKey: Key? = null
) : ActionData(KEY, position, context) {
    companion object {
        @JvmField val KEY = Key("citadel", "place")
    }
}
