package ru.fornax.fornaxcitadel.paper

import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.scheduler.ScheduledTask
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.queue.ActionQueueService
import ru.fornax.fornaxcitadel.paper.scheduler.PaperScheduler
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperActionQueueIntegrationTest {
    @Test
    fun `plugin starts a bounded queue timer and queue suspension preserves pending actions`() {
        queueFixture(maxActionsPerTick = 1).use { fixture ->
            val processed = mutableListOf<Int>()
            registerProcessor(fixture) { action -> processed.add(action.number); ActionResult.SUCCESS }
            assertTrue(fixture.scheduledTasks.isEmpty())
            var unrelatedTicks = 0
            val unrelated = fixture.server.scheduler.runTaskTimer(fixture.plugin, Runnable { unrelatedTicks++ }, 1L, 1L)
            for (number in 1..3) fixture.actions.post(ProbeAction(number))

            fixture.pluginQueue.enable()
            fixture.actionQueue.enable()
            val previousTimer = fixture.scheduledTasks.single { it.task !== unrelated }
            assertEquals(1L, previousTimer.delayTicks)
            assertEquals(1L, previousTimer.periodTicks)
            assertEquals(2, fixture.scheduledTasks.size)
            fixture.runScheduledTasks()
            assertEquals(listOf(1), processed)

            fixture.actionQueue.disable()
            fixture.actionQueue.disable()
            assertTrue(previousTimer.cancelled)
            assertFalse(unrelated.isCancelled)
            fixture.runScheduledTasks()
            assertEquals(listOf(1), processed)
            assertEquals(2, unrelatedTicks)

            fixture.actionQueue.enable()
            previousTimer.action.run()
            assertEquals(listOf(1), processed)
            fixture.runScheduledTasks()
            assertEquals(listOf(1, 2), processed)
            fixture.runScheduledTasks()
            assertEquals(listOf(1, 2, 3), processed)
            fixture.pluginQueue.disable()
            assertTrue(fixture.scheduledTasks.filter { it.task !== unrelated }.all { it.cancelled })
            assertFalse(unrelated.isCancelled)
        }
    }

    @Test
    fun `plugin shutdown cancels the queue timer and clears actions before the next enable`() {
        queueFixture().use { fixture ->
            val processed = mutableListOf<Int>()
            registerProcessor(fixture) { action -> processed.add(action.number); ActionResult.SUCCESS }
            fixture.pluginQueue.enable()
            val previousTimer = fixture.scheduledTasks.single()
            fixture.actions.post(ProbeAction(1))

            fixture.pluginQueue.disable()
            fixture.pluginQueue.disable()
            assertTrue(previousTimer.cancelled)
            fixture.runScheduledTasks()
            assertTrue(processed.isEmpty())

            fixture.pluginQueue.enable()
            fixture.actions.post(ProbeAction(2))
            previousTimer.action.run()
            assertTrue(processed.isEmpty())
            fixture.runScheduledTasks()
            assertEquals(listOf(2), processed)
            fixture.pluginQueue.disable()
        }
    }

    @Test
    fun `plugin shutdown clears pending actions even when timer cancellation fails`() {
        val failure = IllegalStateException("Cancellation failed")
        val timer = object : ScheduledTask {
            override val isCancelled = false
            override fun cancel() { throw failure }
        }
        val scheduler = object : Scheduler by DirectScheduler {
            override fun runTaskTimer(task: Runnable, delayTicks: Long, periodTicks: Long) = timer
        }
        val actions = ActionManager()
        val queue = ActionQueueService(ActionFacade(actions), scheduler, maxPerTick = 1)
        val pluginQueue = PluginActionQueue(queue, actions)
        pluginQueue.enable()
        actions.post(ProbeAction(1))

        assertSame(failure, assertFailsWith<IllegalStateException> { pluginQueue.disable() })
        assertEquals(0, actions.drainPosted())
    }

    @Test
    fun `failed queue startup clears pending actions before a retry`() {
        queueFixture().use { fixture ->
            val processed = mutableListOf<Int>()
            registerProcessor(fixture) { action -> processed.add(action.number); ActionResult.SUCCESS }
            val failure = IllegalStateException("Scheduler unavailable")
            fixture.schedulerFailure = failure
            fixture.actions.post(ProbeAction(1))

            assertSame(failure, assertFailsWith<IllegalStateException> {
                fixture.pluginQueue.enable()
            })
            assertTrue(fixture.scheduledTasks.isEmpty())
            assertEquals(0, fixture.actions.drainPosted())
            fixture.schedulerFailure = null
            fixture.pluginQueue.enable()
            assertEquals(1, fixture.scheduledTasks.size)
            fixture.actions.post(ProbeAction(2))
            fixture.runScheduledTasks()
            assertEquals(listOf(2), processed)
            fixture.pluginQueue.disable()
        }
    }

    @Test
    fun `failed action leaves following actions queued for the next timer invocation`() {
        queueFixture().use { fixture ->
            val attempted = mutableListOf<Int>()
            registerProcessor(fixture) { action ->
                attempted.add(action.number)
                check(action.number != 1) { "Action failed" }
                ActionResult.SUCCESS
            }
            fixture.actions.post(ProbeAction(1))
            fixture.actions.post(ProbeAction(2))
            fixture.pluginQueue.enable()

            fixture.runScheduledTasks()
            assertEquals(listOf(1), attempted)
            assertEquals(1, fixture.scheduledTasks.size)
            fixture.runScheduledTasks()
            assertEquals(listOf(1, 2), attempted)
            fixture.pluginQueue.disable()
        }
    }

    private fun queueFixture(maxActionsPerTick: Int = 10) = PaperTestFixture(
        initializeSystems = false,
        startQueue = false,
        queueSchedulerFactory = ::PaperScheduler,
        maxActionsPerTick = maxActionsPerTick
    )

    private fun registerProcessor(fixture: PaperTestFixture, process: (ProbeAction) -> ActionResult) {
        fixture.actions.registerProcessor(object : ActionProcessor<ProbeAction>() {
            override val actionClass = ProbeAction::class.java
            override val actionKey = ProbeAction.KEY
            override fun execute(action: ProbeAction) = process(action)
        })
    }

    private class ProbeAction(val number: Int) : Action(KEY, UUID(1L, 2L), 1, 64, 2) {
        companion object {
            val KEY = Key("test", "queue_probe")
        }
    }
}
