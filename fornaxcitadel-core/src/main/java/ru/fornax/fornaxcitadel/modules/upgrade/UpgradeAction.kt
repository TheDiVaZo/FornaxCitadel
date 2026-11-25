package ru.fornax.fornaxcitadel.modules.upgrade

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.util.Key

/** Changes an existing reinforcement's definition using a platform item; requires explicit approval. */
class UpgradeAction(context: ActionContext, position: CitadelPosition, val definitionKey: Key) :
    Action(KEY, position, context) {
    companion object {
        @JvmField val KEY = Key("citadel", "upgrade")
    }
}
