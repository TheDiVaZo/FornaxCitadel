package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID

/** Attribution only: a source or a missing player never grants permission by itself. */
data class ActionContext(val actorId: UUID?, val source: Key) {
    companion object {
        @JvmField
        val SYSTEM = ActionContext(null, Key("fornaxcitadel", "system"))
    }
}
