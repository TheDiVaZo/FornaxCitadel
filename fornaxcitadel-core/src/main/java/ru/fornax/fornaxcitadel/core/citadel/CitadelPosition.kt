package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.facade.ICitadelReference
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import java.util.UUID

/** Immutable global block coordinates. Storage bounds are checked when accessing a chunk. */
data class CitadelPosition(
    override val world: UUID,
    override val x: Int,
    override val y: Int,
    override val z: Int
) : ICitadelReference {
    companion object {
        /** Lets platform adapters reject unsupported positions without loading or allocating a chunk. */
        @JvmStatic
        fun isSupported(x: Int, y: Int, z: Int): Boolean =
            (x shr 4) in Short.MIN_VALUE..Short.MAX_VALUE &&
                (z shr 4) in Short.MIN_VALUE..Short.MAX_VALUE &&
                y >= ChunkCord.MIN_Y && y < ChunkCord.MIN_Y + ChunkCord.WORLD_HEIGHT
    }
}
