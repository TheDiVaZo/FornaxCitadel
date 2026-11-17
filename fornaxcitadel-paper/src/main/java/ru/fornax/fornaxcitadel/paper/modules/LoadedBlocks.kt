package ru.fornax.fornaxcitadel.paper.modules

import org.bukkit.Server
import org.bukkit.World
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException

/** Checks world and reinforcement data before callers access Bukkit blocks. Never loads chunks. */
internal object LoadedBlocks {
    fun requireAvailable(server: Server, manager: ComponentManager, position: CitadelPosition): World {
        val world = server.getWorld(position.world) ?: throw CitadelResolutionUnavailableException(position)
        return requireAvailable(world, manager, position.x, position.y, position.z)
    }

    /** Accepts scalar coordinates so successful ray steps need no position allocation. */
    fun requireAvailable(world: World, manager: ComponentManager, x: Int, y: Int, z: Int): World {
        if (!CitadelPosition.isSupported(x, y, z) ||
            y !in world.minHeight until world.maxHeight ||
            !world.isChunkLoaded(x shr 4, z shr 4) ||
            !manager.isChunkLoaded(world.uid, x shr 4, z shr 4)) {
            throw CitadelResolutionUnavailableException(CitadelPosition(world.uid, x, y, z))
        }
        return world
    }
}
