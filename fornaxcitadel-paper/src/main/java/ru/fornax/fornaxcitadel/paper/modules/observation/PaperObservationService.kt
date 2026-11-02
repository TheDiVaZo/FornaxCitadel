package ru.fornax.fornaxcitadel.paper.modules.observation

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.observation.Observation
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationService
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationSubscription
import java.util.UUID
import java.util.logging.Level

/** Owns online-player registrations and the core observation timer, independently of search strategy. */
class PaperObservationService(
    private val plugin: Plugin,
    private val service: ObservationService<Player>,
    private val observation: Observation<Player>
) : LifecycleService, Listener {
    private val viewers = LinkedHashMap<UUID, Viewer>()
    private var generation: Any? = null

    override fun enable() {
        checkMainThread()
        if (generation != null) return
        generation = Any()
        try {
            service.enable()
            plugin.server.pluginManager.registerEvents(this, plugin)
            for (player in plugin.server.onlinePlayers) register(player)
        } catch (failure: Throwable) {
            try {
                disable()
            } catch (cleanupFailure: Throwable) {
                if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    override fun disable() {
        checkMainThread()
        if (generation == null) return
        generation = null
        HandlerList.unregisterAll(this)
        val previous = viewers.values.toList()
        viewers.clear()
        try {
            // Invalidate the core run before callbacks can start another one.
            service.disable()
        } finally {
            for (viewer in previous) release(viewer)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        if (generation == null) return
        try {
            register(event.player)
        } catch (failure: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not observe player ${event.player.uniqueId}", failure)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val viewer = viewers[event.player.uniqueId]?.takeIf { it.player === event.player } ?: return
        viewers.remove(event.player.uniqueId, viewer)
        release(viewer)
    }

    private fun register(player: Player) {
        val current = generation ?: return
        if (!player.isOnline) return
        val existing = viewers[player.uniqueId]
        if (existing?.player === player) return
        if (existing != null) {
            viewers.remove(player.uniqueId, existing)
            release(existing)
        }
        val viewer = Viewer(player)
        viewers[player.uniqueId] = viewer
        try {
            viewer.subscription = service.registerViewer(player.uniqueId, player, observation)
            if (generation !== current || viewers[player.uniqueId] !== viewer) release(viewer)
        } catch (failure: Throwable) {
            viewers.remove(player.uniqueId, viewer)
            throw failure
        }
    }

    private fun release(viewer: Viewer) {
        val subscription = viewer.subscription
        viewer.subscription = null
        try {
            subscription?.close()
        } catch (failure: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not stop observing player ${viewer.player.uniqueId}", failure)
        }
    }

    private fun checkMainThread() = check(plugin.server.isPrimaryThread) {
        "Player observation lifecycle must run on the main thread"
    }

    private class Viewer(val player: Player) { var subscription: ObservationSubscription? = null }
}
