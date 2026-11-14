package ru.fornax.fornaxcitadel.core.pool

import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import java.util.UUID

/** Interns coordinates with signed 16-bit x and z values. Access is synchronized on this pool. */
class WXZCordPool {
    private val worlds = HashMap<UUID, Int2ObjectMap<WXZCord>>()

    /**
     * Returns the same instance for equal coordinates until that entry is removed.
     *
     * @throws IllegalArgumentException if x or z is outside -32768..32767.
     */
    @Synchronized
    fun get(world: UUID, x: Int, z: Int): WXZCord {
        val position = packPosition(x, z)
        val coordinates = worlds.getOrPut(world) { Int2ObjectOpenHashMap() }
        val existing = coordinates.get(position)
        if (existing != null) return existing

        val cord = WXZCord(world, x, z)
        coordinates.put(position, cord)
        return cord
    }

    /**
     * Removes only the cached [expected] instance, preserving a replacement at the same coordinates.
     * Existing references remain valid; the next [get] after removal creates a new instance.
     *
     * @throws IllegalArgumentException if x or z is outside -32768..32767.
     */
    @Synchronized
    fun remove(expected: WXZCord): Boolean {
        val position = packPosition(expected.x, expected.z)
        val coordinates = worlds[expected.world] ?: return false
        if (coordinates.get(position) !== expected) return false
        coordinates.remove(position)
        if (coordinates.isEmpty()) worlds.remove(expected.world)
        return true
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
