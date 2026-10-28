package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID

open class Action(
    val type: Key,
    val citadelW: UUID,
    val citadelX: Int,
    val citadelY: Int,
    val citadelZ: Int
) {

}