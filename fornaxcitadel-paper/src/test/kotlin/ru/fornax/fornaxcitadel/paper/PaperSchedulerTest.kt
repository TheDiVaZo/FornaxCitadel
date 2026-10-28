package ru.fornax.fornaxcitadel.paper

import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.paper.scheduler.PaperScheduler
import java.util.concurrent.Callable
import java.util.concurrent.CompletionException
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

        val result = scheduler.schedule(WXZCord(fixture.worldId, 0, 0), Callable {
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

        val result = scheduler.schedule(WXZCord(fixture.worldId, 0, 0), Callable<Int> {
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
        val result = scheduler.schedule(WXZCord(fixture.worldId, 0, 0), Callable { executed = true })
        val task = fixture.scheduledTasks.single()
        fixture.mainThread = true

        scheduler.close()

        assertTrue(task.cancelled)
        assertTrue(result.isCompletedExceptionally)
        fixture.runScheduledTasks()
        assertFalse(executed)
        fixture.mainThread = false
        assertTrue(scheduler.schedule(WXZCord(fixture.worldId, 0, 0), Callable { 1 }).isCompletedExceptionally)
    }
}
