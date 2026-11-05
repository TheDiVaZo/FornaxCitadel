package ru.fornax.fornaxcitadel.paper.modules.hpdisplay

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationSystem
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationTarget
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthService
import java.util.UUID
import java.util.logging.Level

/**
 * Displays missing health as client-only block cracks on the owning server thread.
 * Each target has its own negative animation ID, separate from vanilla entity IDs. No blocks
 * are changed or chunks loaded. Only potentially visible overlays are retained for cleanup.
 * Unchanged stages are resent on the first observation pass after 20 server ticks; this keeps
 * client animations alive without an additional timer. Larger observation intervals delay it.
 */
class CrackHealthObservationSystem(
    private val plugin: Plugin,
    private val health: HealthService
) : ObservationSystem<Player> {
    override val requiredComponents = setOf(Health::class.java)

    private val overlays = HashMap<ObservationTarget, MutableMap<UUID, Overlay>>()

    override fun onViewerEnter(citadel: CitadelHandle, target: ObservationTarget, viewer: Player) {
        update(citadel, target, listOf(viewer), force = true)
    }

    override fun tick(citadel: CitadelHandle, target: ObservationTarget, viewers: List<Player>) {
        update(citadel, target, viewers, force = false)
    }

    override fun onViewerLeave(citadel: ObservationTarget, viewer: Player) {
        checkMainThread()
        clear(citadel, viewer, forget = true)
    }

    override fun onDetach(citadel: ObservationTarget) {
        checkMainThread()
        val previous = overlays.remove(citadel) ?: return
        for (overlay in previous.values) {
            safely(citadel, overlay.viewer) { sendClear(citadel, overlay.viewer) }
        }
    }

    private fun update(citadel: CitadelHandle, target: ObservationTarget, viewers: List<Player>, force: Boolean) {
        checkMainThread()
        // Refresh once for the complete audience, including time-dependent HealthBehavior.
        val component = try {
            health.refreshHealth(citadel)
        } catch (failure: Exception) {
            clearTarget(target)
            throw failure
        }
        if (component == null) {
            clearTarget(target)
            return
        }
        val progress = (1.0 - component.health / component.maxHealth).coerceIn(0.0, 0.99).toFloat()
        // Paper maps positive progress to floor(9 * progress), with zero reserved for clearing.
        val stage = if (progress == 0.0f) -1 else (9 * progress).toInt()
        val now = plugin.server.currentTick
        val position = target.position
        val chunkKey = Chunk.getChunkKey(position.x shr 4, position.z shr 4)
        for (viewer in viewers) {
            safely(target, viewer) {
                if (!viewer.isOnline || viewer.world.uid != position.world || !viewer.isChunkSent(chunkKey) || stage < 0) {
                    clear(target, viewer, forget = false)
                    return@safely
                }
                val audience = overlays.getOrPut(target) { HashMap() }
                val previous = audience[viewer.uniqueId]
                val overlay = if (previous?.viewer === viewer) previous else Overlay(viewer).also {
                    audience[viewer.uniqueId] = it
                }
                if (!force && !overlay.pending && overlay.stage == stage &&
                    now - overlay.sentAt in 0 until RESEND_INTERVAL_TICKS) return@safely

                // Keep uncertain sends for cleanup and retry even if an exception follows delivery.
                overlay.pending = true
                viewer.sendBlockDamage(location(target, viewer), progress, -target.runtimeId)
                overlay.stage = stage
                overlay.sentAt = now
                overlay.pending = false
            }
        }
    }

    private fun clearTarget(target: ObservationTarget) {
        val previous = overlays[target]?.values?.toList() ?: return
        for (overlay in previous) clear(target, overlay.viewer, forget = false)
    }

    private fun clear(target: ObservationTarget, viewer: Player, forget: Boolean) {
        val audience = overlays[target] ?: return
        val overlay = audience[viewer.uniqueId]?.takeIf { it.viewer === viewer } ?: return
        try {
            overlay.pending = true
            safely(target, viewer) {
                sendClear(target, viewer)
                audience.remove(viewer.uniqueId, overlay)
            }
        } finally {
            // Closed subscriptions cannot be retried; active ones retain a failed clear until next pass.
            if (forget) audience.remove(viewer.uniqueId, overlay)
            if (audience.isEmpty()) overlays.remove(target, audience)
        }
    }

    private fun sendClear(target: ObservationTarget, viewer: Player) {
        if (viewer.isOnline) viewer.sendBlockDamage(location(target, viewer), 0.0f, -target.runtimeId)
    }

    private fun location(target: ObservationTarget, viewer: Player): Location = with(target.position) {
        // The packet identifies an overlay by source ID. Clearing also works after a world switch
        // or unload, without resolving the old world or accessing its blocks.
        Location(viewer.world, x.toDouble(), y.toDouble(), z.toDouble())
    }

    private fun checkMainThread() = check(plugin.server.isPrimaryThread) {
        "Health crack display must run on the main thread"
    }

    private inline fun safely(target: ObservationTarget, viewer: Player, action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            plugin.logger.log(Level.WARNING, "Could not update health cracks at $target for ${viewer.uniqueId}", failure)
        }
    }

    private class Overlay(val viewer: Player) {
        var stage = -1
        var sentAt = 0
        var pending = true
    }

    private companion object {
        const val RESEND_INTERVAL_TICKS = 20
    }
}
