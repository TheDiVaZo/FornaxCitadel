package ru.fornax.fornaxcitadel.modules.queue

import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.scheduler.ManualScheduler
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActionQueueServiceTest {
    @Test
    fun `timer drains FIFO within per tick budget and enable is idempotent`() {
        val fixture = Fixture(maxPerTick = 2)
        repeat(5) { fixture.actions.post(ProbeAction(it)) }
        assertTrue(fixture.scheduler.tasks.isEmpty())

        fixture.service.enable()
        fixture.service.enable()
        val timer = fixture.scheduler.tasks.single()
        assertEquals(1L, timer.delayTicks)
        assertEquals(1L, timer.periodTicks)
        assertTrue(fixture.executed.isEmpty())

        fixture.scheduler.tick()
        assertEquals(listOf(0, 1), fixture.executed)
        fixture.scheduler.tick()
        assertEquals(listOf(0, 1, 2, 3), fixture.executed)
        fixture.scheduler.tick()
        assertEquals(listOf(0, 1, 2, 3, 4), fixture.executed)
    }

    @Test
    fun `disable preserves pending actions and stale callbacks cannot drain a new run`() {
        val fixture = Fixture()
        fixture.service.disable()
        fixture.service.enable()
        val oldTimer = fixture.scheduler.tasks.single()
        fixture.actions.post(ProbeAction(1))

        fixture.service.disable()
        fixture.service.disable()
        assertTrue(oldTimer.isCancelled)
        oldTimer.callback.run()
        fixture.scheduler.tick()
        assertTrue(fixture.executed.isEmpty())

        fixture.service.enable()
        oldTimer.callback.run()
        assertTrue(fixture.executed.isEmpty())
        fixture.scheduler.tick()
        assertEquals(listOf(1), fixture.executed)
        assertEquals(2, fixture.scheduler.tasks.size)
    }

    @Test
    fun `failed action is reported and remaining actions execute on next tick`() {
        val fixture = Fixture()
        val failure = IllegalStateException("Action failed")
        fixture.failuresByNumber[1] = failure
        repeat(3) { fixture.actions.post(ProbeAction(it)) }
        fixture.service.enable()

        fixture.scheduler.tick()
        assertEquals(listOf(0, 1), fixture.executed)
        assertSame(failure, fixture.failures.single())
        fixture.scheduler.tick()
        assertEquals(listOf(0, 1, 2), fixture.executed)
        assertEquals(1, fixture.failures.size)
    }

    @Test
    fun `scheduling failure rolls back activation and retry preserves pending actions`() {
        val fixture = Fixture()
        val failure = IllegalStateException("Scheduler unavailable")
        fixture.scheduler.timerFailure = failure
        fixture.actions.post(ProbeAction(1))

        assertSame(failure, assertFailsWith<IllegalStateException> { fixture.service.enable() })
        fixture.service.disable()
        fixture.scheduler.timerFailure = null
        fixture.service.enable()
        fixture.scheduler.tick()
        assertEquals(listOf(1), fixture.executed)
        assertEquals(1, fixture.scheduler.tasks.size)
    }

    @Test
    fun `per tick limit must be positive`() {
        assertFailsWith<IllegalArgumentException> { Fixture(0) }
        assertFailsWith<IllegalArgumentException> { Fixture(-1) }
    }

    private class Fixture(maxPerTick: Int = 1024) {
        val scheduler = ManualScheduler()
        val actions = ActionManager()
        val executed = mutableListOf<Int>()
        val failures = mutableListOf<Exception>()
        val failuresByNumber = mutableMapOf<Int, Exception>()
        val service = ActionQueueService(ActionFacade(actions), scheduler, maxPerTick, failures::add)

        init {
            actions.registerProcessor(object : ActionProcessor<ProbeAction>() {
                override val actionClass = ProbeAction::class.java
                override val actionKey = PROBE_KEY
                override fun execute(action: ProbeAction): ActionResult {
                    executed.add(action.number)
                    failuresByNumber[action.number]?.let { throw it }
                    return ActionResult.SUCCESS
                }
            })
        }
    }

    private class ProbeAction(val number: Int) : Action(PROBE_KEY, UUID(0, 1), 0, 0, 0)

    private companion object {
        val PROBE_KEY = Key("test", "queued_probe")
    }
}
