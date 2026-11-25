package ru.fornax.fornaxcitadel.paper.modules

import org.bukkit.Server
import org.bukkit.World
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import java.util.UUID

internal typealias ChunkLoadCheck = (world: UUID, chunkX: Int, chunkZ: Int) -> Boolean

/** Checks world and reinforcement data before callers access Bukkit blocks. Never loads chunks. */
internal object LoadedBlocks {
    fun requireAvailable(server: Server, isChunkLoaded: ChunkLoadCheck, position: CitadelPosition): World {
        val world = server.getWorld(position.world) ?: throw CitadelResolutionUnavailableException(position)
        return requireAvailable(world, isChunkLoaded, position.x, position.y, position.z)
    }

    /** Accepts scalar coordinates so successful ray steps need no position allocation. */
    fun requireAvailable(world: World, isChunkLoaded: ChunkLoadCheck, x: Int, y: Int, z: Int): World {
        if (!CitadelPosition.isSupported(x, y, z) ||
            y !in world.minHeight until world.maxHeight ||
            !world.isChunkLoaded(x shr 4, z shr 4) ||
            !isChunkLoaded(world.uid, x shr 4, z shr 4)) {
            throw CitadelResolutionUnavailableException(CitadelPosition(world.uid, x, y, z))
        }
        return world
    }
}
