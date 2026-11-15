package ru.fornax.fornaxcitadel.modules.repair

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.util.Key

/** Requests full repair using a platform item; requires explicit validator approval. */
class RepairAction(context: ActionContext, position: CitadelPosition) : ActionData(KEY, position, context) {
    companion object {
        @JvmField val KEY = Key("citadel", "repair")
    }
}
