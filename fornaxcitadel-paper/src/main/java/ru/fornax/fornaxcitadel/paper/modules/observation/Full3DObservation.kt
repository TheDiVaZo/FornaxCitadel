package ru.fornax.fornaxcitadel.paper.modules.observation

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleListener
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleNotifications
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.systems.observation.Observation
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationPublisher
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationSubscription
import ru.fornax.fornaxcitadel.paper.modules.chunks.ChunkDataListener
import ru.fornax.fornaxcitadel.paper.modules.chunks.PaperChunkService
import java.util.UUID
import java.util.logging.Level

/**
 * Observes a cuboid of 16x16x16 cells around each player. A radius of three includes seven cells
 * on each axis. Scans existing core entities by chunk and Y range; never loads Bukkit chunks.
 * Movement inside a cell does not change membership. Changes are coalesced until the next tick,
 * when the actual player location and final core state are read. Initial publication is synchronous.
 *
 * Subscriptions share one listener and at most one pending refresh task. The last close releases
 * them. [close] clears all subscriptions and publications; this instance may subsequently be reused.
 */
class Full3DObservation @JvmOverloads constructor(
    private val plugin: Plugin,
    private val manager: ComponentManager,
    private val lifecycle: CitadelLifecycleNotifications,
    private val chunks: PaperChunkService,
    private val settings: Full3DObservationSettings = Full3DObservationSettings()
) : Observation<Player>, Listener, AutoCloseable {
    private val sessions = LinkedHashMap<UUID, Session>()
    private val dirty = LinkedHashMap<Session, Boolean>()
    private var generation: Any? = null
    private var pending: RefreshTask? = null
    private val chunkListener = ChunkDataListener { world, x, z ->
        for (session in sessions.values.toList()) {
            val cell = session.cell ?: continue
            if (cell.world == world && cell.containsChunk(x, z)) request(session, force = true)
        }
    }
    private val citadelListener = object : CitadelLifecycleListener {
        override fun onPlaced(position: CitadelPosition, runtimeId: Int) = changed(position)
        override fun onRemoved(position: CitadelPosition, runtimeId: Int, cause: RemovalCause) = changed(position)
    }

    override fun observe(viewer: Player, publisher: ObservationPublisher): ObservationSubscription {
        checkMainThread()
        require(viewer.isOnline) { "Only online players can be observed" }
        val id = viewer.uniqueId
        require(!sessions.containsKey(id)) { "Player $id already has a subscription to this observation" }
        val session = Session(viewer, publisher)
        sessions[id] = session
        try {
            if (generation == null) attach()
            refresh(session, force = true)
        } catch (failure: Throwable) {
            try {
                session.close()
            } catch (cleanupFailure: Throwable) {
                if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        return session
    }

    /** Forces a new full publication, e.g. after a custom integration changes data directly. */
    fun refresh(viewer: Player) {
        checkMainThread()
        findSession(viewer)?.let { request(it, force = true) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (cellAt(event.from) == cellAt(event.to)) return
        findSession(event.player)?.let { request(it, force = false) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        findSession(event.player)?.let { request(it, force = false) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        findSession(event.player)?.let { request(it, force = false) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        findSession(event.player)?.let { request(it, force = false) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val session = findSession(event.player) ?: return
        session.close()
        safely("clear disconnected player ${event.player.uniqueId}") { session.publisher.publish(emptyList()) }
    }

    override fun close() {
        checkMainThread()
        val previous = sessions.values.toList()
        sessions.clear()
        previous.forEach { it.active = false }
        detach()
        for (session in previous) {
            safely("clear player ${session.player.uniqueId}") { session.publisher.publish(emptyList()) }
        }
    }

    private fun attach() {
        generation = Any()
        try {
            lifecycle.addListener(citadelListener)
            chunks.addDataListener(chunkListener)
            plugin.server.pluginManager.registerEvents(this, plugin)
        } catch (failure: Throwable) {
            detach()
            throw failure
        }
    }

    private fun detach() {
        generation = null
        val previous = pending
        pending = null
        dirty.clear()
        HandlerList.unregisterAll(this)
        lifecycle.removeListener(citadelListener)
        chunks.removeDataListener(chunkListener)
        previous?.task?.cancel()
    }

    private fun changed(position: CitadelPosition) {
        for (session in sessions.values.toList()) {
            val cell = session.cell ?: continue
            if (cell.world == position.world && cell.containsChunk(position.x shr 4, position.z shr 4) &&
                kotlin.math.abs(cell.y.toLong() - (position.y shr 4)) <= settings.verticalRadiusSections.toLong()) {
                request(session, force = true)
            }
        }
    }

    private fun request(session: Session, force: Boolean) {
        checkMainThread()
        if (!isCurrent(session)) return
        dirty[session] = force || dirty[session] == true
        if (pending != null) return
        val current = generation ?: return
        val batch = RefreshTask()
        pending = batch
        try {
            batch.task = plugin.server.scheduler.runTask(plugin, Runnable {
                if (generation !== current || pending !== batch) return@Runnable
                pending = null
                val work = dirty.toMap()
                dirty.clear()
                for ((viewer, forced) in work) {
                    if (isCurrent(viewer)) safely("refresh player ${viewer.player.uniqueId}") {
                        refresh(viewer, forced)
                    }
                }
            })
        } catch (failure: Throwable) {
            if (pending === batch) pending = null
            throw failure
        }
    }

    private fun refresh(session: Session, force: Boolean) {
        val cell = if (session.player.isOnline) cellAt(session.player.location) else null
        if (!force && session.cell == cell) return
        val citadels = if (cell == null) emptyList() else scan(cell)
        if (!isCurrent(session)) return
        val previous = session.cell
        // Publication may run gameplay callbacks. Their invalidations must use the new area.
        session.cell = cell
        try {
            session.publisher.publish(citadels)
        } catch (failure: Throwable) {
            if (isCurrent(session)) session.cell = previous
            throw failure
        }
    }

    private fun scan(cell: Cell): List<CitadelHandle> {
        val horizontal = settings.horizontalRadiusChunks.toLong()
        val vertical = settings.verticalRadiusSections.toLong()
        val minX = maxOf(Short.MIN_VALUE.toLong(), cell.x.toLong() - horizontal)
        val maxX = minOf(Short.MAX_VALUE.toLong(), cell.x.toLong() + horizontal)
        val minZ = maxOf(Short.MIN_VALUE.toLong(), cell.z.toLong() - horizontal)
        val maxZ = minOf(Short.MAX_VALUE.toLong(), cell.z.toLong() + horizontal)
        // The facade clips this range to storage bounds; Paper does not depend on its layout.
        val minY = maxOf(Int.MIN_VALUE.toLong(), (cell.y.toLong() - vertical) * 16)
        val maxY = minOf(Int.MAX_VALUE.toLong(),
            (cell.y.toLong() + vertical + 1) * 16 - 1)
        if (minX > maxX || minZ > maxZ || minY > maxY) return emptyList()
        val result = ArrayList<CitadelHandle>()
        for (x in minX.toInt()..maxX.toInt()) {
            for (z in minZ.toInt()..maxZ.toInt()) {
                manager.forEachCitadel(cell.world, x, z, minY.toInt(), maxY.toInt(), result::add)
            }
        }
        return result
    }

    private fun cellAt(location: Location): Cell? = location.world?.let {
        Cell(it.uid, location.blockX shr 4, location.blockY shr 4, location.blockZ shr 4)
    }

    private fun Cell.containsChunk(chunkX: Int, chunkZ: Int): Boolean =
        kotlin.math.abs(x.toLong() - chunkX) <= settings.horizontalRadiusChunks.toLong() &&
            kotlin.math.abs(z.toLong() - chunkZ) <= settings.horizontalRadiusChunks.toLong()

    private fun findSession(player: Player): Session? =
        sessions[player.uniqueId]?.takeIf { it.player === player }

    private fun isCurrent(session: Session): Boolean =
        session.active && sessions[session.player.uniqueId] === session

    private fun checkMainThread() = check(plugin.server.isPrimaryThread) {
        "Observation access must run on the main thread"
    }

    private inline fun safely(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not $operation for full 3D observation", failure)
        }
    }

    private inner class Session(val player: Player, val publisher: ObservationPublisher) : ObservationSubscription {
        var active = true
        var cell: Cell? = null

        override fun close() {
            checkMainThread()
            if (!active) return
            active = false
            sessions.remove(player.uniqueId, this)
            dirty.remove(this)
            if (sessions.isEmpty()) detach()
        }
    }

    private data class Cell(val world: UUID, val x: Int, val y: Int, val z: Int)
    private class RefreshTask { var task: BukkitTask? = null }
}
