package ru.fornax.fornaxcitadel.paper

import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
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
    fun `module starts a bounded queue timer and queue suspension preserves pending actions`() {
        queueFixture(maxActionsPerTick = 1).use { fixture ->
            val processed = mutableListOf<Int>()
            registerProcessor(fixture) { action -> processed.add(action.number); ActionResult.SUCCESS }
            assertTrue(fixture.scheduledTasks.isEmpty())
            var unrelatedTicks = 0
            val unrelated = fixture.server.scheduler.runTaskTimer(fixture.plugin, Runnable { unrelatedTicks++ }, 1L, 1L)
            for (number in 1..3) fixture.module.actions.post(ProbeAction(number))

            fixture.module.enable()
            fixture.module.enable()
            val previousTimer = fixture.scheduledTasks.single { it.task !== unrelated }
            assertEquals(1L, previousTimer.delayTicks)
            assertEquals(1L, previousTimer.periodTicks)
            assertEquals(2, fixture.scheduledTasks.size)
            fixture.runScheduledTasks()
            assertEquals(listOf(1), processed)

            fixture.module.actionQueue.disable()
            fixture.module.actionQueue.disable()
            assertTrue(previousTimer.cancelled)
            assertFalse(unrelated.isCancelled)
            fixture.runScheduledTasks()
            assertEquals(listOf(1), processed)
            assertEquals(2, unrelatedTicks)

            fixture.module.actionQueue.enable()
            previousTimer.action.run()
            assertEquals(listOf(1), processed)
            fixture.runScheduledTasks()
            assertEquals(listOf(1, 2), processed)
            fixture.runScheduledTasks()
            assertEquals(listOf(1, 2, 3), processed)
            fixture.module.disable()
            assertTrue(fixture.scheduledTasks.filter { it.task !== unrelated }.all { it.cancelled })
            assertFalse(unrelated.isCancelled)
        }
    }

    @Test
    fun `module shutdown cancels the queue timer and clears actions before the next enable`() {
        queueFixture().use { fixture ->
            val processed = mutableListOf<Int>()
            registerProcessor(fixture) { action -> processed.add(action.number); ActionResult.SUCCESS }
            fixture.module.enable()
            val previousTimer = fixture.scheduledTasks.single()
            fixture.module.actions.post(ProbeAction(1))

            fixture.module.disable()
            fixture.module.disable()
            assertTrue(previousTimer.cancelled)
            fixture.runScheduledTasks()
            assertTrue(processed.isEmpty())

            fixture.module.enable()
            fixture.module.actions.post(ProbeAction(2))
            previousTimer.action.run()
            assertTrue(processed.isEmpty())
            fixture.runScheduledTasks()
            assertEquals(listOf(2), processed)
        }
    }

    @Test
    fun `failed module startup can be retried without registering a duplicate timer`() {
        queueFixture().use { fixture ->
            val processed = mutableListOf<Int>()
            registerProcessor(fixture) { action -> processed.add(action.number); ActionResult.SUCCESS }
            val failure = IllegalStateException("Scheduler unavailable")
            fixture.schedulerFailure = failure
            fixture.module.actions.post(ProbeAction(1))

            assertSame(failure, assertFailsWith<IllegalStateException> { fixture.module.enable() })
            assertTrue(fixture.scheduledTasks.isEmpty())
            fixture.schedulerFailure = null
            fixture.module.enable()
            fixture.module.enable()
            assertEquals(1, fixture.scheduledTasks.size)
            fixture.module.actions.post(ProbeAction(2))
            fixture.runScheduledTasks()
            assertEquals(listOf(2), processed)
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
            fixture.module.actions.post(ProbeAction(1))
            fixture.module.actions.post(ProbeAction(2))
            fixture.module.enable()

            fixture.runScheduledTasks()
            assertEquals(listOf(1), attempted)
            assertEquals(1, fixture.scheduledTasks.size)
            fixture.runScheduledTasks()
            assertEquals(listOf(1, 2), attempted)
        }
    }

    private fun queueFixture(maxActionsPerTick: Int = 10) = PaperTestFixture(
        initializeSystems = false,
        initializeModule = false,
        moduleSchedulerFactory = ::PaperScheduler,
        maxActionsPerTick = maxActionsPerTick
    )

    private fun registerProcessor(fixture: PaperTestFixture, process: (ProbeAction) -> ActionResult) {
        fixture.module.actions.processorCoordinator.registerProcessor(object : ActionProcessor<ProbeAction>() {
            override val actionClass = ProbeAction::class.java
            override val actionKey = ProbeAction.KEY
            override fun execute(action: ProbeAction) = process(action)
        })
    }

    private class ProbeAction(val number: Int) : ActionData(KEY, UUID(1L, 2L), 1, 64, 2) {
        companion object {
            val KEY = Key("test", "queue_probe")
        }
    }
}
