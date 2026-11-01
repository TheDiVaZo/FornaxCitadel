package ru.fornax.fornaxcitadel.core.scheduler

import ru.fornax.fornaxcitadel.core.storage.WXZCord
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture

interface Scheduler {
    /**
     * Schedules [task] on the game thread responsible for [location].
     *
     * A Paper implementation may use the server main thread, while a Folia
     * implementation may use the region thread that owns [location].
     * The task may run inline when the caller already owns that thread.
     * The returned future is completed with either the task result or failure.
     */
    fun <T> runTask(location: WXZCord, task: Callable<T>): CompletableFuture<T>

    /**
     * Runs [task] periodically on the owning game thread without a chunk binding.
     * Callbacks are sequential. The first callback is deferred even when [delayTicks] is zero.
     * [delayTicks] must be nonnegative and [periodTicks] must be positive.
     */
    fun runTaskTimer(task: Runnable, delayTicks: Long, periodTicks: Long): ScheduledTask
}
