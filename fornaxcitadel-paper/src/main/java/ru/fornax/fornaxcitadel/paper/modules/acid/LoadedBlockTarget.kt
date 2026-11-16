package ru.fornax.fornaxcitadel.paper.modules.acid

import org.bukkit.FluidCollisionMode
import org.bukkit.Server
import org.bukkit.block.Block
import org.bukkit.entity.Player
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import kotlin.math.floor

/** Voxel traversal checks availability before Bukkit sees a position; it never requests a chunk. */
internal object LoadedBlockTarget {
    fun blockAt(server: Server, manager: ComponentManager, position: CitadelPosition): Block {
        val world = server.getWorld(position.world) ?: throw CitadelResolutionUnavailableException(position)
        if (!CitadelPosition.isSupported(position.x, position.y, position.z) || position.y !in world.minHeight until world.maxHeight ||
            !world.isChunkLoaded(position.x shr 4, position.z shr 4) ||
            !manager.isChunkLoaded(position.world, position.x shr 4, position.z shr 4)) throw CitadelResolutionUnavailableException(position)
        return world.getBlockAt(position.x, position.y, position.z)
    }

    fun find(player: Player, distance: Int, manager: ComponentManager): Block? {
        val eye = player.eyeLocation
        val world = player.world
        val direction = eye.direction.normalize()
        val coordinates = intArrayOf(eye.blockX, eye.blockY, eye.blockZ)
        val origin = doubleArrayOf(eye.x, eye.y, eye.z)
        val ray = doubleArrayOf(direction.x, direction.y, direction.z)
        val steps = ray.map { if (it > 0) 1 else if (it < 0) -1 else 0 }
        val delta = ray.map { if (it == 0.0) Double.POSITIVE_INFINITY else kotlin.math.abs(1.0 / it) }
        val next = DoubleArray(3) { axis ->
            if (steps[axis] == 0) Double.POSITIVE_INFINITY
            else ((floor(origin[axis]) + if (steps[axis] > 0) 1.0 else 0.0) - origin[axis]) / ray[axis]
        }
        var travelled = 0.0
        while (travelled <= distance) {
            val (x, y, z) = coordinates
            if (y !in world.minHeight until world.maxHeight || !CitadelPosition.isSupported(x, y, z)) return null
            if (!world.isChunkLoaded(x shr 4, z shr 4) || !manager.isChunkLoaded(world.uid, x shr 4, z shr 4)) {
                throw CitadelResolutionUnavailableException(CitadelPosition(world.uid, x, y, z))
            }
            val block = world.getBlockAt(x, y, z)
            if (!block.isEmpty && block.rayTrace(eye, direction, distance.toDouble(), FluidCollisionMode.NEVER) != null) return block
            val axis = (0..2).minBy { next[it] }
            travelled = next[axis]
            coordinates[axis] += steps[axis]
            next[axis] += delta[axis]
        }
        return null
    }
}
