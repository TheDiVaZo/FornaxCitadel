package ru.fornax.fornaxcitadel.core.storage

import java.util.UUID

data class WXZCord(
    val world: UUID,
    val x: Int,
    val z: Int
) : Comparable<WXZCord> {
    override fun compareTo(other: WXZCord): Int {
        val worldComp = world.compareTo(other.world)
        if (worldComp != 0) {
            return worldComp
        }
        val xComp = x.compareTo(other.x)
        if (xComp != 0) {
            return xComp
        }
        return z.compareTo(other.z)
    }
}
