package ru.fornax.fornaxcitadel.modules.remove

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

/** Authorizes explicit administrative requests, never the completion of accepted lethal damage. */
fun interface RemovalPolicy {
    fun mayRemove(context: ActionContext, citadel: CitadelHandle): Boolean

    companion object {
        @JvmField val DENY = RemovalPolicy { _, _ -> false }
    }
}
