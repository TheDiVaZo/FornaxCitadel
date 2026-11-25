package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID

open class Action @JvmOverloads constructor(
    val type: Key,
    val position: CitadelPosition,
    val context: ActionContext = ActionContext.SYSTEM
) {
    @JvmOverloads
    constructor(
        type: Key,
        citadelW: UUID,
        citadelX: Int,
        citadelY: Int,
        citadelZ: Int,
        context: ActionContext = ActionContext.SYSTEM
    ) : this(type, CitadelPosition(citadelW, citadelX, citadelY, citadelZ), context)
}
