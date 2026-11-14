package ru.fornax.fornaxcitadel.modules.dismantle

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.util.Key

/** Requests dismantling through a platform processor; requires explicit validator approval. */
class DismantleAction(context: ActionContext, position: CitadelPosition) : ActionData(KEY, position, context) {
    companion object {
        @JvmField val KEY = Key("citadel", "dismantle")
    }
}
