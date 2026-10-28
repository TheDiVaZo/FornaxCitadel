package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import java.util.UUID

open class ActionData @JvmOverloads constructor(
    val type: Key,
    val citadelW: UUID,
    val citadelX: Int,
    val citadelY: Int,
    val citadelZ: Int,
    val context: ActionContext = ActionContext.SYSTEM
) {
    constructor(type: Key, position: CitadelPosition, context: ActionContext) :
        this(type, position.world, position.x, position.y, position.z, context)

    val position: CitadelPosition
        get() = CitadelPosition(citadelW, citadelX, citadelY, citadelZ)
}
