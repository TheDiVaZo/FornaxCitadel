package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComponentManagerPoolTest {
    @Test
    fun `pending unload retains the same key for lookups and loads`() {
        val fixture = Fixture()
        val original = fixture.lookup()
        fixture.load()
        assertSame(original, fixture.storage.loads.single())

        val unloading = fixture.manager.unloadChunk(WORLD, X, Z)

        assertFalse(unloading.isDone)
        assertSame(original, fixture.storage.unloads.single().location)
        assertSame(original, fixture.lookup())
        fixture.load()
        assertSame(original, fixture.storage.loads.last())
    }

    @Test
    fun `failed unload retains the cached key and propagates its failure`() {
        val fixture = Fixture()
        val original = fixture.lookup()
        val unloading = fixture.manager.unloadChunk(WORLD, X, Z)
        val failure = IllegalStateException("Unload failed")

        fixture.storage.unloads.single().completion.completeExceptionally(failure)

        assertSame(failure, assertFailsWith<CompletionException> { unloading.join() }.cause)
        assertSame(original, fixture.lookup())
        fixture.load()
        assertSame(original, fixture.storage.loads.single())
        fixture.manager.unloadChunk(WORLD, X, Z)
        assertSame(original, fixture.storage.unloads.last().location)
    }

    @Test
    fun `successful unload evicts its key before the returned future notifies callers`() {
        val fixture = Fixture()
        val original = fixture.lookup()
        val neighbor = fixture.lookup(X + 1)
        val unloading = fixture.manager.unloadChunk(WORLD, X, Z)
        val keyAfterCompletion = unloading.thenApply { fixture.lookup() }

        assertTrue(fixture.storage.unloads.single().completion.complete(null))

        val replacement = keyAfterCompletion.join()
        assertEquals(original, replacement)
        assertNotSame(original, replacement)
        assertSame(replacement, fixture.lookup())
        assertSame(neighbor, fixture.lookup(X + 1))
        fixture.load()
        assertSame(replacement, fixture.storage.loads.single())
    }

    @Test
    fun `storage completion on a worker thread evicts without an owner thread task`() {
        val fixture = Fixture()
        val original = fixture.lookup()
        val unloading = fixture.manager.unloadChunk(WORLD, X, Z)
        val completion = fixture.storage.unloads.single().completion
        val caller = Thread.currentThread()

        val worker = CompletableFuture.supplyAsync {
            assertTrue(completion.complete(null))
            Thread.currentThread()
        }.get(5, TimeUnit.SECONDS)
        unloading.get(5, TimeUnit.SECONDS)

        assertNotSame(caller, worker)
        val replacement = fixture.lookup()
        assertEquals(original, replacement)
        assertNotSame(original, replacement)
    }

    @Test
    fun `cancelling the returned unload future neither cancels storage nor skips eviction`() {
        val fixture = Fixture()
        val original = fixture.lookup()
        val unloading = fixture.manager.unloadChunk(WORLD, X, Z)
        val completion = fixture.storage.unloads.single().completion

        assertTrue(unloading.cancel(false))

        assertTrue(unloading.isCancelled)
        assertFalse(completion.isCancelled)
        assertFalse(completion.isDone)
        assertSame(original, fixture.lookup())
        assertTrue(completion.complete(null))
        assertTrue(unloading.isCancelled)
        val replacement = fixture.lookup()
        assertEquals(original, replacement)
        assertNotSame(original, replacement)
    }

    @Test
    fun `late older unload completion does not remove a freshly cached equal key`() {
        val fixture = Fixture()
        val original = fixture.lookup()
        val older = fixture.manager.unloadChunk(WORLD, X, Z)
        val newer = fixture.manager.unloadChunk(WORLD, X, Z)
        fixture.storage.unloads.forEach { assertSame(original, it.location) }

        fixture.storage.unloads[1].completion.complete(null)
        newer.join()
        val replacement = fixture.lookup()
        assertEquals(original, replacement)
        assertNotSame(original, replacement)

        fixture.storage.unloads[0].completion.complete(null)
        older.join()

        assertSame(replacement, fixture.lookup())
        fixture.load()
        assertSame(replacement, fixture.storage.loads.single())
    }

    private class Fixture {
        val storage = RecordingStorage()
        val manager = ComponentManager(storage, TypeManager())

        fun lookup(x: Int = X): WXZCord {
            manager.isChunkLoaded(WORLD, x, Z)
            return storage.lookups.last()
        }

        fun load() { manager.loadChunk(WORLD, X, Z).join() }
    }

    private class RecordingStorage : CitadelStorage {
        val lookups = mutableListOf<WXZCord>()
        val loads = mutableListOf<WXZCord>()
        val unloads = mutableListOf<PendingUnload>()

        override fun getIfChunkLoaded(location: WXZCord): ChunkCord? {
            lookups.add(location)
            return null
        }

        override fun loadChunk(location: WXZCord): CompletableFuture<ChunkCord> {
            loads.add(location)
            return CompletableFuture.completedFuture(ChunkCord(location))
        }

        override fun unloadChunk(location: WXZCord): CompletableFuture<Void> {
            val completion = CompletableFuture<Void>()
            unloads.add(PendingUnload(location, completion))
            return completion
        }

        override fun updateOpaque(chunk: ChunkCord): Nothing = error("Unexpected opaque update")
        override fun updateOpaque(key: Key): Nothing = error("Unexpected opaque update")
        override fun saveAll(): Nothing = error("Unexpected save")
        override fun closeAndSaveAll(): Nothing = error("Unexpected close")
    }

    private class PendingUnload(val location: WXZCord, val completion: CompletableFuture<Void>)

    private companion object {
        val WORLD: UUID = UUID(12L, 34L)
        const val X = -3
        const val Z = 7
    }
}
