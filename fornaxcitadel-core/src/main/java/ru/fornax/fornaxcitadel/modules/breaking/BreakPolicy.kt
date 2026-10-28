package ru.fornax.fornaxcitadel.modules.breaking

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

enum class BreakDecision { DENY, DAMAGE, REMOVE }

/** Read-only decision after common validators. Must not mutate or retain live components. */
fun interface BreakPolicy {
    fun decide(context: ActionContext, citadel: CitadelHandle): BreakDecision
}

object DamageBreakPolicy : BreakPolicy {
    override fun decide(context: ActionContext, citadel: CitadelHandle) = BreakDecision.DAMAGE
}

object UnbreakablePolicy : BreakPolicy {
    override fun decide(context: ActionContext, citadel: CitadelHandle) = BreakDecision.DENY
}
