package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

/**
 * Read-only authorization rule for ordinary damage or explicit destruction.
 * Type rules apply to ordinary damage; explicit destruction uses the module's separate policy.
 * Must not mutate or retain live components. Destruction checks may run more than once per action.
 */
fun interface DamagePolicy {
    fun allowsDamage(context: ActionContext, citadel: CitadelHandle): Boolean

    companion object {
        @JvmField val ALLOW = DamagePolicy { _, _ -> true }
        @JvmField val DENY = DamagePolicy { _, _ -> false }
    }
}
