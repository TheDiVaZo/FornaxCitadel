package ru.fornax.fornaxcitadel.paper.modules.queue

import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import java.util.logging.Level

/** Drains posted actions on the main thread while retaining the queue across a temporary pause. */
class PaperActionQueueService(
    private val plugin: Plugin,
    private val actions: ActionManager,
    private val maxPerTick: Int
) : LifecycleService {
    private var activeRun: QueueRun? = null

    init {
        require(maxPerTick > 0) { "Action limit per tick must be positive" }
    }

    override fun enable() {
        if (activeRun != null) return
        val run = QueueRun()
        activeRun = run
        try {
            run.task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                if (activeRun !== run) return@Runnable
                try {
                    actions.drainPosted(maxPerTick)
                } catch (exception: Exception) {
                    plugin.logger.log(Level.SEVERE, "Queued citadel action failed; remaining actions stay queued", exception)
                }
            }, 1L, 1L)
        } catch (failure: Throwable) {
            activeRun = null
            run.task?.cancel()
            throw failure
        }
    }

    override fun disable() {
        val run = activeRun ?: return
        activeRun = null
        run.task?.cancel()
    }

    private class QueueRun {
        var task: BukkitTask? = null
    }
}
