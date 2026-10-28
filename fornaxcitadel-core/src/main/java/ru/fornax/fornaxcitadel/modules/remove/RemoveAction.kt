package ru.fornax.fornaxcitadel.modules.remove

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.util.Key

/** Explicit administrative request. Setting a context source does not authorize removal. */
class RemoveAction(context: ActionContext, position: CitadelPosition) : ActionData(KEY, position, context) {
    companion object {
        @JvmField val KEY = Key("citadel", "remove")
    }
}
