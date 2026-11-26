package ru.fornax.fornaxcitadel.modules.acid

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.util.Key

/** Activates the acid at the targeted position. Its destruction actions carry the same context. */
open class AcidAction(context: ActionContext, position: CitadelPosition) : Action(KEY, position, context) {
    companion object {
        @JvmField val KEY = Key("citadel", "acid")
    }
}
