package ru.fornax.fornaxcitadel.paper

import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.paper.scheduler.PaperScheduler
import java.util.concurrent.Callable
import java.util.concurrent.CompletionException
import java.util.concurrent.RejectedExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperSchedulerTest {
    @Test
    fun `main thread work runs inline and remains usable for shutdown saves`() {
        val fixture = PaperTestFixture(load = false)
        val scheduler = PaperScheduler(fixture.plugin)
        fixture.enabled = false
        scheduler.close()
        var executed = false

        val result = scheduler.runTask(WXZCord(fixture.worldId, 0, 0), Callable {
            executed = true
            42
        })

        assertTrue(executed)
        assertTrue(result.isDone)
        assertEquals(42, result.join())
        assertTrue(fixture.scheduledTasks.isEmpty())
    }

    @Test
    fun `background request waits for main thread and propagates task failure`() {
        val fixture = PaperTestFixture(load = false)
        val scheduler = PaperScheduler(fixture.plugin)
        fixture.mainThread = false
        val failure = IllegalStateException("Expected task failure")
        var executed = false

        val result = scheduler.runTask(WXZCord(fixture.worldId, 0, 0), Callable<Int> {
            assertTrue(fixture.mainThread)
            executed = true
            throw failure
        })

        assertFalse(executed)
        assertFalse(result.isDone)
        fixture.runScheduledTasks()
        assertTrue(executed)
        assertSame(failure, assertFailsWith<CompletionException> { result.join() }.cause)
    }

    @Test
    fun `closing cancels queued work and completes waiting futures exceptionally`() {
        val fixture = PaperTestFixture(load = false)
        val scheduler = PaperScheduler(fixture.plugin)
        fixture.mainThread = false
        var executed = false
        val result = scheduler.runTask(WXZCord(fixture.worldId, 0, 0), Callable { executed = true })
        val task = fixture.scheduledTasks.single()
        fixture.mainThread = true

        scheduler.close()

        assertTrue(task.cancelled)
        assertTrue(result.isCompletedExceptionally)
        task.action.run()
        fixture.runScheduledTasks()
        assertFalse(executed)
        fixture.mainThread = false
        assertTrue(scheduler.runTask(WXZCord(fixture.worldId, 0, 0), Callable { 1 }).isCompletedExceptionally)
    }

    @Test
    fun `timer preserves tick delay and period and runs on the main thread`() {
        val fixture = PaperTestFixture(load = false)
        val scheduler = PaperScheduler(fixture.plugin)
        fixture.mainThread = false
        var executions = 0

        val timer = scheduler.runTaskTimer(Runnable {
            assertTrue(fixture.mainThread)
            executions++
        }, 2L, 3L)

        val task = fixture.scheduledTasks.single()
        assertEquals(2L, task.delayTicks)
        assertEquals(3L, task.periodTicks)
        assertEquals(0, executions)
        fixture.advanceTicks(1)
        assertEquals(0, executions)
        fixture.advanceTicks(1)
        assertEquals(1, executions)
        fixture.advanceTicks(2)
        assertEquals(1, executions)
        fixture.advanceTicks(1)
        assertEquals(2, executions)
        assertFalse(timer.isCancelled)
        scheduler.close()
    }

    @Test
    fun `zero delay timer is deferred even on the main thread`() {
        val fixture = PaperTestFixture(load = false)
        val scheduler = PaperScheduler(fixture.plugin)
        var executions = 0

        scheduler.runTaskTimer(Runnable { executions++ }, 0L, 1L)

        assertEquals(0, executions)
        assertEquals(0L, fixture.scheduledTasks.single().delayTicks)
        fixture.advanceTicks(1)
        assertEquals(1, executions)
        scheduler.close()
    }

    @Test
    fun `timer cancellation is idempotent and blocks late callbacks`() {
        val fixture = PaperTestFixture(load = false)
        val scheduler = PaperScheduler(fixture.plugin)
        var executions = 0
        val timer = scheduler.runTaskTimer(Runnable { executions++ }, 1L, 1L)
        val task = fixture.scheduledTasks.single()

        timer.cancel()
        timer.cancel()
        task.action.run()
        fixture.advanceTicks(2)

        assertTrue(timer.isCancelled)
        assertTrue(task.cancelled)
        assertEquals(0, executions)
        scheduler.close()
    }

    @Test
    fun `timer handle reflects cancellation by Bukkit and ignores late callback`() {
        val fixture = PaperTestFixture(load = false)
        val scheduler = PaperScheduler(fixture.plugin)
        var executed = false
        val timer = scheduler.runTaskTimer(Runnable { executed = true }, 1L, 1L)
        val task = fixture.scheduledTasks.single()

        task.task.cancel()

        assertTrue(timer.isCancelled)
        timer.cancel()
        task.action.run()
        assertFalse(executed)
        scheduler.close()
    }

    @Test
    fun `closing cancels only owned timers and rejects new timers`() {
        val fixture = PaperTestFixture(load = false)
        val scheduler = PaperScheduler(fixture.plugin)
        var ownedExecutions = 0
        var externalExecutions = 0
        val timer = scheduler.runTaskTimer(Runnable { ownedExecutions++ }, 1L, 1L)
        val ownedTask = fixture.scheduledTasks.single()
        val external = fixture.server.scheduler.runTaskTimer(fixture.plugin, Runnable { externalExecutions++ }, 1L, 1L)

        scheduler.close()
        scheduler.close()
        ownedTask.action.run()
        fixture.advanceTicks(1)

        assertTrue(timer.isCancelled)
        assertTrue(ownedTask.cancelled)
        assertFalse(external.isCancelled)
        assertEquals(0, ownedExecutions)
        assertEquals(1, externalExecutions)
        assertFailsWith<RejectedExecutionException> {
            scheduler.runTaskTimer(Runnable {}, 1L, 1L)
        }
        external.cancel()
    }

    @Test
    fun `invalid timer arguments fail before scheduling`() {
        val fixture = PaperTestFixture(load = false)
        val scheduler = PaperScheduler(fixture.plugin)

        assertFailsWith<IllegalArgumentException> { scheduler.runTaskTimer(Runnable {}, -1L, 1L) }
        assertFailsWith<IllegalArgumentException> { scheduler.runTaskTimer(Runnable {}, 0L, 0L) }
        assertFailsWith<IllegalArgumentException> { scheduler.runTaskTimer(Runnable {}, 0L, -1L) }
        assertTrue(fixture.scheduledTasks.isEmpty())
    }

    @Test
    fun `Bukkit scheduling failures propagate and do not prevent later registrations`() {
        val fixture = PaperTestFixture(load = false)
        val scheduler = PaperScheduler(fixture.plugin)
        fixture.mainThread = false
        val failure = IllegalStateException("Scheduler rejected task")
        fixture.schedulerFailure = failure

        val result = scheduler.runTask(WXZCord(fixture.worldId, 0, 0), Callable { 1 })
        assertSame(failure, assertFailsWith<CompletionException> { result.join() }.cause)
        assertSame(failure, assertFailsWith<IllegalStateException> {
            scheduler.runTaskTimer(Runnable {}, 1L, 1L)
        })
        assertTrue(fixture.scheduledTasks.isEmpty())

        fixture.schedulerFailure = null
        val timer = scheduler.runTaskTimer(Runnable {}, 1L, 1L)
        fixture.mainThread = true
        scheduler.close()
        assertTrue(timer.isCancelled)
    }

    @Test
    fun `disabled plugin rejects timers and background requests`() {
        val fixture = PaperTestFixture(load = false)
        val scheduler = PaperScheduler(fixture.plugin)
        fixture.enabled = false

        assertFailsWith<RejectedExecutionException> {
            scheduler.runTaskTimer(Runnable {}, 1L, 1L)
        }
        fixture.mainThread = false
        val result = scheduler.runTask(WXZCord(fixture.worldId, 0, 0), Callable { 1 })
        assertTrue(assertFailsWith<CompletionException> { result.join() }.cause is RejectedExecutionException)
        assertTrue(fixture.scheduledTasks.isEmpty())
    }

    @Test
    fun `callback failure leaves timer available for later ticks`() {
        val fixture = PaperTestFixture(load = false)
        val scheduler = PaperScheduler(fixture.plugin)
        var executions = 0
        val failure = IllegalStateException("Timer callback failed")
        val timer = scheduler.runTaskTimer(Runnable {
            if (++executions == 1) throw failure
        }, 1L, 1L)

        assertSame(failure, assertFailsWith<IllegalStateException> { fixture.advanceTicks(1) })
        assertFalse(timer.isCancelled)
        fixture.advanceTicks(1)
        assertEquals(2, executions)
        scheduler.close()
    }
}
