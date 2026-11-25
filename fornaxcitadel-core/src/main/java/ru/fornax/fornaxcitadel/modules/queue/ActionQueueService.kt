package ru.fornax.fornaxcitadel.modules.queue

import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.scheduler.ScheduledTask
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import java.util.logging.Level
import java.util.logging.Logger

/** Drains posted actions each tick. Disabling the service preserves pending actions. */
class ActionQueueService(
    private val actions: ActionFacade,
    private val scheduler: Scheduler,
    private val settings: ActionSettings
) : LifecycleService {
    private var activeRun: QueueRun? = null

    @JvmOverloads
    constructor(
        actions: ActionFacade,
        scheduler: Scheduler,
        maxPerTick: Int,
        failureHandler: (Exception) -> Unit = DEFAULT_FAILURE_HANDLER
    ) : this(actions, scheduler, ActionSettings(maxPerTick, failureHandler))

    override fun enable() {
        if (activeRun != null) return
        val run = QueueRun()
        activeRun = run
        try {
            run.task = scheduler.runTaskTimer(Runnable {
                if (activeRun !== run) return@Runnable
                try {
                    actions.drainPosted(settings.maxActionsPerTick)
                } catch (failure: Exception) {
                    settings.actionFailureHandler(failure)
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
        var task: ScheduledTask? = null
    }

    companion object {
        private val logger = Logger.getLogger(ActionQueueService::class.java.name)

        @JvmField
        val DEFAULT_FAILURE_HANDLER: (Exception) -> Unit = { failure ->
            logger.log(Level.SEVERE, "Queued citadel action failed; remaining actions stay queued", failure)
        }
    }
}
