package ru.fornax.fornaxcitadel.core

import java.util.UUID

data class CitadelPosition(
    val world: UUID,
    val x: Int,
    val y: Int,
    val z: Int
)
