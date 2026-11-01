package ru.fornax.fornaxcitadel.paper.modules.chunks

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent

/** Adapts Bukkit chunk events to the chunk service. */
class PaperChunkListener(private val service: PaperChunkService) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun onLoad(event: ChunkLoadEvent) = service.onLoad(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onUnload(event: ChunkUnloadEvent) = service.onUnload(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) = service.onWorldUnload(event)
}
