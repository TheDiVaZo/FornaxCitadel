package ru.fornax.fornaxcitadel.modules.ownership

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.modules.breaking.BreakDecision
import ru.fornax.fornaxcitadel.modules.breaking.BreakPolicy

object OwnerOrDamagePolicy : BreakPolicy {
    override fun decide(context: ActionContext, citadel: CitadelHandle): BreakDecision {
        val owner = citadel.getComponent(Owner::class.java)
        return if (context.actorId != null && context.actorId == owner?.playerId) {
            BreakDecision.REMOVE
        } else BreakDecision.DAMAGE
    }
}
