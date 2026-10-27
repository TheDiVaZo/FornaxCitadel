package ru.fornax.fornaxcitadel.core.pool

import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import java.util.UUID

/** Interns coordinates with signed 16-bit x and z values. Not thread-safe. */
class WXZCordPool {
    private val worlds = HashMap<UUID, Int2ObjectMap<WXZCord>>()

    /**
     * Returns the same instance for equal coordinates within this pool.
     *
     * @throws IllegalArgumentException if x or z is outside -32768..32767.
     */
    fun get(world: UUID, x: Int, z: Int): WXZCord {
        val position = packPosition(x, z)
        val coordinates = worlds.getOrPut(world) { Int2ObjectOpenHashMap() }
        val existing = coordinates.get(position)
        if (existing != null) return existing

        val cord = WXZCord(world, x, z)
        coordinates.put(position, cord)
        return cord
    }

    private fun packPosition(x: Int, z: Int): Int {
        require(x in Short.MIN_VALUE..Short.MAX_VALUE) {
            "x must be in -32768..32767 to fit in a packed Int: $x"
        }
        require(z in Short.MIN_VALUE..Short.MAX_VALUE) {
            "z must be in -32768..32767 to fit in a packed Int: $z"
        }
        return (x shl 16) or (z and 0xFFFF)
    }
}
