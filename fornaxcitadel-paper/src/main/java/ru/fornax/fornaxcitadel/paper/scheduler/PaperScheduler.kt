package ru.fornax.fornaxcitadel.paper.scheduler

import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import ru.fornax.fornaxcitadel.core.scheduler.ScheduledTask
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException

/** Paper's main thread owns every core chunk. Inline work remains available during shutdown. */
class PaperScheduler(private val plugin: Plugin) : Scheduler, AutoCloseable {
    private val server = plugin.server
    private val lock = Any()
    private val pending = HashMap<CompletableFuture<*>, BukkitTask>()
    private val timers = HashSet<RepeatingTask>()
    private var closed = false

    override fun <T> runTask(location: WXZCord, task: Callable<T>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        if (server.isPrimaryThread) {
            complete(result, task)
            return result
        }

        synchronized(lock) {
            if (closed || !plugin.isEnabled) {
                result.completeExceptionally(RejectedExecutionException("Paper scheduler is stopping"))
                return result
            }
            try {
                pending[result] = server.scheduler.runTask(plugin, Runnable {
                    synchronized(lock) { pending.remove(result) }
                    if (!result.isDone) complete(result, task)
                })
            } catch (failure: Exception) {
                result.completeExceptionally(failure)
            }
        }
        return result
    }

    override fun runTaskTimer(task: Runnable, delayTicks: Long, periodTicks: Long): ScheduledTask {
        require(delayTicks >= 0L) { "Timer delay must not be negative" }
        require(periodTicks > 0L) { "Timer period must be positive" }
        return synchronized(lock) {
            if (closed || !plugin.isEnabled) {
                throw RejectedExecutionException("Paper scheduler is stopping")
            }
            val timer = RepeatingTask(task)
            timer.bukkitTask = server.scheduler.runTaskTimer(plugin, timer, delayTicks, periodTicks)
            timers.add(timer)
            timer
        }
    }

    /** Call on the main thread before the final synchronous core save. */
    override fun close() {
        check(server.isPrimaryThread) { "Close the Paper scheduler on the main thread" }
        val (cancelled, cancelledTimers) = synchronized(lock) {
            closed = true
            val queued = pending.entries.map { it.key to it.value }
            pending.clear()
            queued to timers.toList().mapNotNull { it.cancelLocked() }
        }
        for ((result, task) in cancelled) {
            result.completeExceptionally(RejectedExecutionException("Paper scheduler stopped"))
            task.cancel()
        }
        cancelledTimers.forEach(BukkitTask::cancel)
    }

    private inner class RepeatingTask(private val action: Runnable) : ScheduledTask, Runnable {
        var bukkitTask: BukkitTask? = null
        private var cancelled = false

        override val isCancelled: Boolean
            get() = synchronized(lock) {
                if (!cancelled && bukkitTask?.isCancelled == true) {
                    cancelled = true
                    bukkitTask = null
                    timers.remove(this)
                }
                cancelled
            }

        override fun run() {
            if (!isCancelled) action.run()
        }

        override fun cancel() {
            synchronized(lock) { cancelLocked() }?.cancel()
        }

        fun cancelLocked(): BukkitTask? {
            if (cancelled) return null
            cancelled = true
            timers.remove(this)
            return bukkitTask.also { bukkitTask = null }
        }
    }

    private fun <T> complete(result: CompletableFuture<T>, task: Callable<T>) {
        try {
            result.complete(task.call())
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        }
    }
}
