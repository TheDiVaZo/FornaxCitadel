package ru.fornax.fornaxcitadel.modules.breaking

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.util.Key

class BreakAction(context: ActionContext, position: CitadelPosition, val damage: Double) :
    ActionData(KEY, position, context) {
    init { require(damage.isFinite() && damage >= 0.0) { "Damage must be finite and non-negative" } }
    companion object {
        @JvmField val KEY = Key("citadel", "break")
    }
}
