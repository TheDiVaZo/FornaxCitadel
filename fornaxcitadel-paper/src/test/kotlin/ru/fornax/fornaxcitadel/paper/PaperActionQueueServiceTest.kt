package ru.fornax.fornaxcitadel.paper

import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.modules.queue.PaperActionQueueService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperActionQueueServiceTest {
    @Test
    fun `queue service respects tick limits and survives pauses without losing pending actions`() {
        PaperTestFixture(initializeSystems = false).use { fixture ->
            val processed = mutableListOf<Int>()
            registerProcessor(fixture) { action -> processed.add(action.number); ActionResult.SUCCESS }
            val service = PaperActionQueueService(fixture.plugin, fixture.module.actions, 1)
            var unrelatedTicks = 0
            val unrelated = fixture.server.scheduler.runTaskTimer(fixture.plugin, Runnable { unrelatedTicks++ }, 1L, 1L)
            for (number in 1..3) fixture.module.actions.post(ProbeAction(number))

            service.disable()
            service.enable()
            service.enable()
            val previousTimer = fixture.scheduledTasks.single { it.task !== unrelated }
            assertEquals(1L, previousTimer.delayTicks)
            assertEquals(1L, previousTimer.periodTicks)
            assertEquals(2, fixture.scheduledTasks.size)
            fixture.runScheduledTasks()
            assertEquals(listOf(1), processed)

            service.disable()
            service.disable()
            assertTrue(previousTimer.cancelled)
            assertFalse(unrelated.isCancelled)
            fixture.runScheduledTasks()
            assertEquals(listOf(1), processed)
            assertEquals(2, unrelatedTicks)

            service.enable()
            previousTimer.action.run()
            assertEquals(listOf(1), processed)
            fixture.runScheduledTasks()
            assertEquals(listOf(1, 2), processed)
            fixture.runScheduledTasks()
            assertEquals(listOf(1, 2, 3), processed)
            service.disable()
        }
    }

    @Test
    fun `failed queue startup can be retried without registering a duplicate task`() {
        PaperTestFixture(initializeSystems = false).use { fixture ->
            val processed = mutableListOf<Int>()
            registerProcessor(fixture) { action -> processed.add(action.number); ActionResult.SUCCESS }
            val service = PaperActionQueueService(fixture.plugin, fixture.module.actions, 4)
            val failure = IllegalStateException("Scheduler unavailable")
            fixture.schedulerFailure = failure
            fixture.module.actions.post(ProbeAction(1))

            assertSame(failure, assertFailsWith<IllegalStateException> { service.enable() })
            assertTrue(fixture.scheduledTasks.isEmpty())
            service.disable()
            fixture.schedulerFailure = null
            service.enable()
            service.enable()
            assertEquals(1, fixture.scheduledTasks.size)
            fixture.runScheduledTasks()
            assertEquals(listOf(1), processed)
            service.disable()
        }
    }

    @Test
    fun `failed action leaves following actions queued for the next timer invocation`() {
        PaperTestFixture(initializeSystems = false).use { fixture ->
            val attempted = mutableListOf<Int>()
            registerProcessor(fixture) { action ->
                attempted.add(action.number)
                check(action.number != 1) { "Action failed" }
                ActionResult.SUCCESS
            }
            val service = PaperActionQueueService(fixture.plugin, fixture.module.actions, 10)
            fixture.module.actions.post(ProbeAction(1))
            fixture.module.actions.post(ProbeAction(2))
            service.enable()

            fixture.runScheduledTasks()
            assertEquals(listOf(1), attempted)
            assertEquals(1, fixture.scheduledTasks.size)
            fixture.runScheduledTasks()
            assertEquals(listOf(1, 2), attempted)
            service.disable()
        }
    }

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
