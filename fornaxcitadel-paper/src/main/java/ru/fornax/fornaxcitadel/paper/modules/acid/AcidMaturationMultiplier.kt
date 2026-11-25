package ru.fornax.fornaxcitadel.paper.modules.acid

import org.bukkit.Server
import ru.fornax.fornaxcitadel.core.facade.ReadableHandle
import ru.fornax.fornaxcitadel.modules.health.MaturationMultiplier
import ru.fornax.fornaxcitadel.paper.modules.ChunkLoadCheck

/** Resolves acid at the saved reinforcement anchor, without loading chunks. */
internal class AcidMaturationMultiplier(
    private val registry: AcidRegistry,
    private val server: Server,
    private val isChunkLoaded: ChunkLoadCheck
) : MaturationMultiplier {
    override fun getMultiplier(citadel: ReadableHandle): Double {
        if (!registry.settings.enabled) return 1.0
        val block = LoadedBlockTarget.blockAt(server, isChunkLoaded, citadel.position)
        return registry.find(block.type)?.maturationMultiplier ?: 1.0
    }
}
