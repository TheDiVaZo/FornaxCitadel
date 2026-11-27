package ru.fornax.fornaxcitadel.modules.acid

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.util.Key

/** Consumes the acid source and destroys its selected targets within this action's execution. */
open class AcidAction(context: ActionContext, position: CitadelPosition) : Action(KEY, position, context) {
    companion object {
        @JvmField val KEY = Key("citadel", "acid")
    }
}
