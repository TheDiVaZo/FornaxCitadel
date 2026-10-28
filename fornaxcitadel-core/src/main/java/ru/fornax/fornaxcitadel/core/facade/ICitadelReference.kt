package ru.fornax.fornaxcitadel.core.facade

import java.util.UUID

interface ICitadelReference {
    val world: UUID
    val x: Int
    val y: Int
    val z: Int
}