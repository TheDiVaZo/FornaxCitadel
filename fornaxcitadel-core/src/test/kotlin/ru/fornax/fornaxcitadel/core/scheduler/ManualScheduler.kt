package ru.fornax.fornaxcitadel.core.scheduler

import ru.fornax.fornaxcitadel.core.storage.WXZCord
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture

/** Test scheduler: location calls are immediate; timers run only when ticks are advanced. */
class ManualScheduler : Scheduler {
    val tasks = mutableListOf<TimerTask>()
    var timerFailure: RuntimeException? = null
    private var currentTick = 0L

    override fun <T> runTask(location: WXZCord, task: Callable<T>): CompletableFuture<T> = try {
        CompletableFuture.completedFuture(task.call())
    } catch (failure: Throwable) {
        CompletableFuture.failedFuture(failure)
    }

    override fun runTaskTimer(task: Runnable, delayTicks: Long, periodTicks: Long): ScheduledTask {
        require(delayTicks >= 0)
        require(periodTicks > 0)
        timerFailure?.let { throw it }
        return TimerTask(task, delayTicks, periodTicks, currentTick + maxOf(1L, delayTicks)).also(tasks::add)
    }

    fun tick() {
        currentTick++
        tasks.toList().filter { !it.isCancelled && it.nextTick <= currentTick }.forEach {
            it.nextTick += it.periodTicks
            it.callback.run()
        }
    }

    class TimerTask(
        val callback: Runnable,
        val delayTicks: Long,
        val periodTicks: Long,
        var nextTick: Long
    ) : ScheduledTask {
        override var isCancelled = false
            private set
        var cancellationFailure: RuntimeException? = null

        override fun cancel() {
            if (isCancelled) return
            isCancelled = true
            cancellationFailure?.let { throw it }
        }
    }
}
