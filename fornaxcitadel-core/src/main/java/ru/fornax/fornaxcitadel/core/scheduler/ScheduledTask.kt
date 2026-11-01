package ru.fornax.fornaxcitadel.core.scheduler

/** A scheduled task whose cancellation is safe to repeat. */
interface ScheduledTask {
    fun cancel()

    val isCancelled: Boolean
}
