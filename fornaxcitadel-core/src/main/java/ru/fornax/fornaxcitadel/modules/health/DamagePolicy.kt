package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

/** Read-only type rule shared by every damage source. Must not mutate or retain live components. */
fun interface DamagePolicy {
    fun allowsDamage(context: ActionContext, citadel: CitadelHandle): Boolean

    companion object {
        @JvmField val ALLOW = DamagePolicy { _, _ -> true }
        @JvmField val DENY = DamagePolicy { _, _ -> false }
    }
}
