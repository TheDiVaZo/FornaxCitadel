package ru.fornax.fornaxcitadel.paper.modules.acid

import org.bukkit.Server
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.modules.health.MaturationMultiplier

/** Resolves acid at the saved reinforcement anchor, without loading chunks. */
internal class AcidMaturationMultiplier(
    private val registry: AcidRegistry,
    private val server: Server,
    private val manager: ComponentManager
) : MaturationMultiplier {
    override fun getMultiplier(citadel: CitadelHandle): Double {
        if (!registry.settings.enabled) return 1.0
        val block = LoadedBlockTarget.blockAt(server, manager, citadel.position)
        return registry.find(block.type)?.maturationMultiplier ?: 1.0
    }
}
