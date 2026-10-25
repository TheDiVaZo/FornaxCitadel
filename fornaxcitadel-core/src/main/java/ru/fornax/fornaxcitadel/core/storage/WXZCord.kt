package ru.fornax.fornaxcitadel.core.storage

import java.util.*

open class WXZCord(val world: UUID, val x: Int, val z: Int): Comparable<WXZCord> {
    override fun compareTo(other: WXZCord): Int {
        val worldComp = this.world.compareTo(other.world)
        if (worldComp != 0) {
            return worldComp
        }
        val xComp = this.x.compareTo(other.x)
        if (xComp != 0) {
            return xComp
        }
        return this.z.compareTo(other.z)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WXZCord) return false

        if (x != other.x) return false
        if (z != other.z) return false
        if (world != other.world) return false

        return true
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + z
        result = 31 * result + world.hashCode()
        return result
    }


}
