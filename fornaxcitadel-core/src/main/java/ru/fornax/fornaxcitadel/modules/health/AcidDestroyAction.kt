package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

/** Explicit acid destruction, still visible to every DamageAction validator and handler. */
class AcidDestroyAction(
    context: ActionContext,
    position: CitadelPosition,
    val acidPosition: CitadelPosition,
    val acidRuntimeId: Int,
    val consumingSource: Boolean = false
) : DamageAction(context, position, 0.0, Mode.DESTROY)
