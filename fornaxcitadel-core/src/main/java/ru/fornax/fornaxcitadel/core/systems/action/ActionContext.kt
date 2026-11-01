package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID

/** The responsible actor and immutable source description; neither grants permission by itself. */
data class ActionContext(val actorId: UUID?, val source: ActionSource) {
    companion object {
        @JvmField
        val SYSTEM = ActionContext(null, NamedActionSource(Key("fornaxcitadel", "system")))
    }
}
