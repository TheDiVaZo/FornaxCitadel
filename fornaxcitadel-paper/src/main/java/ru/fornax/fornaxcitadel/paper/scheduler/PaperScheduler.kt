package ru.fornax.fornaxcitadel.paper.scheduler

import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
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
    private var closed = false

    override fun <T> schedule(location: WXZCord, task: Callable<T>): CompletableFuture<T> {
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

    /** Call on the main thread before the final synchronous core save. */
    override fun close() {
        check(server.isPrimaryThread) { "Close the Paper scheduler on the main thread" }
        val cancelled = synchronized(lock) {
            closed = true
            pending.entries.map { it.key to it.value }.also { pending.clear() }
        }
        for ((result, task) in cancelled) {
            task.cancel()
            result.completeExceptionally(RejectedExecutionException("Paper scheduler stopped"))
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
