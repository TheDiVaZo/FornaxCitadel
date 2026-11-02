package ru.fornax.fornaxcitadel.paper.modules.chunks

import java.util.UUID

/**
 * Invalidates a chunk after its core data may have changed availability, including failed unloads.
 * Called on the main thread. Consumers should defer queries until the lifecycle operation finishes.
 */
fun interface ChunkDataListener {
    fun onChunkDataChanged(world: UUID, chunkX: Int, chunkZ: Int)
}
