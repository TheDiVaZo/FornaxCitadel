package ru.fornax.fornaxcitadel.modules.claim

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.util.Key

/** Claims an existing unowned citadel for the acting player; requires explicit validator approval. */
class ClaimAction(context: ActionContext, position: CitadelPosition) : ActionData(KEY, position, context) {
    companion object {
        @JvmField val KEY = Key("citadel", "claim")
    }
}
