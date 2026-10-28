package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.DBStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import java.io.DataInput
import java.io.DataOutput
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SimpleComponentManagerTest {
    @Test
    fun `iteration selects the type and chunk and exposes citadel ids and global coordinates with live components`() {
        val fixture = Fixture()
        val otherWorld = UUID(3L, 4L)
        fixture.manager.registerComponent("test", "other", OtherComponent::class.java)
        fixture.manager.loadChunk(WORLD, -2, 1).join()
        fixture.manager.loadChunk(WORLD, -1, 1).join()
        fixture.manager.loadChunk(otherWorld, -2, 1).join()
        val first = ValueComponent(1)
        val second = ValueComponent(2)
        fixture.manager.putComponent(ValueComponent::class.java, WORLD, -32, -64, 16, first)
        fixture.manager.putComponent(ValueComponent::class.java, WORLD, -17, 319, 31, second)
        fixture.manager.putComponent(OtherComponent::class.java, WORLD, -17, 319, 31, OtherComponent())
        fixture.manager.putComponent(OtherComponent::class.java, WORLD, -30, 20, 19, OtherComponent())
        fixture.manager.putComponent(ValueComponent::class.java, WORLD, -16, 0, 16, ValueComponent(3))
        fixture.manager.putComponent(ValueComponent::class.java, otherWorld, -32, -64, 16, ValueComponent(4))
        fixture.manager.saveAll().join()
        val chunk = assertNotNull(fixture.storage.getIfChunkLoaded(WXZCord(WORLD, -2, 1)))
        val visited = mutableListOf<Pair<Triple<Int, Int, Int>, ValueComponent>>()

        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, -2, 1) { citadelId, x, y, z, component ->
            assertEquals(assertNotNull(chunk.getCitadel(x and 15, y, z and 15)).id, citadelId)
            visited.add(Triple(x, y, z) to component)
        }

        assertEquals(2, visited.size)
        assertSame(first, visited.single { it.first == Triple(-32, -64, 16) }.second)
        assertSame(second, visited.single { it.first == Triple(-17, 319, 31) }.second)
        assertFalse(chunk.isDirty)
    }

    @Test
    fun `iteration skips unloaded and empty chunks and positions without the selected type`() {
        val fixture = Fixture()
        val action: (Int, Int, Int, Int, ValueComponent) -> Unit = { _, _, _, _, _ ->
            error("No matching component should be visited")
        }

        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, action)
        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))
        fixture.load()
        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, action)
        fixture.manager.registerComponent("test", "other", OtherComponent::class.java)
        fixture.manager.putComponent(OtherComponent::class.java, WORLD, 0, 0, 0, OtherComponent())
        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, action)
    }

    @Test
    fun `global block coordinates resolve the correct chunk and local position`() {
        val fixture = Fixture()
        val positions = listOf(-17 to -1, -16 to 16, 0 to 0, 16 to 31)

        for ((index, position) in positions.withIndex()) {
            val (x, z) = position
            val y = if (index % 2 == 0) -64 else 319
            val chunkX = Math.floorDiv(x, 16)
            val chunkZ = Math.floorDiv(z, 16)
            assertNull(fixture.manager.loadChunk(WORLD, chunkX, chunkZ).join())
            val component = ValueComponent(index)

            fixture.manager.putComponent(ValueComponent::class.java, WORLD, x, y, z, component)

            val chunk = assertNotNull(fixture.storage.getIfChunkLoaded(WXZCord(WORLD, chunkX, chunkZ)))
            val entity = assertNotNull(chunk.getCitadel(Math.floorMod(x, 16), y, Math.floorMod(z, 16)))
            val type = assertNotNull(fixture.types.getType(ValueComponent::class.java))
            assertSame(component, chunk.getComponent(entity, type))
            assertSame(component, fixture.manager.getComponent(ValueComponent::class.java, WORLD, x, y, z))
        }
    }

    @Test
    fun `put replace and remove preserve other components and worlds`() {
        val fixture = Fixture()
        val otherWorld = UUID(3L, 4L)
        fixture.load()
        fixture.manager.loadChunk(otherWorld, 0, 0).join()
        fixture.manager.registerComponent("test", "other", OtherComponent::class.java)
        val first = fixture.put(1)
        val replacement = ValueComponent(2)
        val other = OtherComponent()
        val foreign = ValueComponent(3)
        fixture.manager.putComponent(OtherComponent::class.java, WORLD, 0, 0, 0, other)
        fixture.manager.putComponent(ValueComponent::class.java, otherWorld, 0, 0, 0, foreign)

        assertSame(first, fixture.manager.putComponent(ValueComponent::class.java, WORLD, 0, 0, 0, replacement))
        assertSame(replacement, fixture.current())
        assertSame(replacement, fixture.manager.removeComponent(ValueComponent::class.java, WORLD, 0, 0, 0))
        assertNull(fixture.current())
        assertSame(other, fixture.manager.getComponent(OtherComponent::class.java, WORLD, 0, 0, 0))
        assertSame(foreign, fixture.manager.getComponent(ValueComponent::class.java, otherWorld, 0, 0, 0))
        assertTrue(fixture.manager.removeAllComponents(WORLD, 0, 0, 0))
        assertNull(fixture.manager.getComponent(OtherComponent::class.java, WORLD, 0, 0, 0))
        assertFalse(fixture.manager.removeAllComponents(WORLD, 0, 0, 0))
    }

    @Test
    fun `missing data does not trigger loading or silently accept writes`() {
        val fixture = Fixture()
        assertNull(fixture.current())
        assertNull(fixture.manager.removeComponent(ValueComponent::class.java, WORLD, 0, 0, 0))
        assertFalse(fixture.manager.removeAllComponents(WORLD, 0, 0, 0))
        assertFalse(fixture.manager.editComponent(ValueComponent::class.java, WORLD, 0, 0, 0) {
            error("Missing component must not invoke the callback")
        })
        assertFalse(fixture.manager.markDirty(ValueComponent::class.java, WORLD, 0, 0, 0, ValueComponent()))
        assertFailsWith<IllegalStateException> { fixture.put(1) }
        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))

        fixture.load()
        assertTrue(fixture.manager.isChunkLoaded(WORLD, 0, 0))
        assertNull(fixture.current())
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `unregistered types and invalid heights are rejected before inserting data`() {
        val fixture = Fixture()
        fixture.load()

        assertFailsWith<IllegalArgumentException> {
            fixture.manager.putComponent(OtherComponent::class.java, WORLD, 0, 0, 0, OtherComponent())
        }
        for (y in listOf(-65, 320)) {
            assertFailsWith<IllegalArgumentException> {
                fixture.manager.putComponent(ValueComponent::class.java, WORLD, 0, y, 0, ValueComponent())
            }
        }
        assertTrue(fixture.chunk().isEmpty)
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `direct mutation is only saved after explicitly marking the live instance dirty`() {
        val fixture = Fixture()
        fixture.load()
        fixture.put(1)
        fixture.manager.saveAll().join()
        val component = assertNotNull(fixture.current())
        assertFalse(fixture.chunk().isDirty)

        component.value = 2
        fixture.manager.saveAll().join()

        assertEquals(1, fixture.backend.saves.size)
        assertEquals(1, fixture.savedValue())
        assertTrue(fixture.manager.markDirty(ValueComponent::class.java, WORLD, 0, 0, 0, component))
        fixture.manager.saveAll().join()
        assertEquals(2, fixture.savedValue())
    }

    @Test
    fun `edit marks partial changes dirty even when the callback throws`() {
        val fixture = Fixture()
        fixture.load()
        fixture.put(1)
        fixture.manager.saveAll().join()
        val failure = IllegalStateException("Failed after mutation")

        val actual = assertFailsWith<IllegalStateException> {
            fixture.manager.editComponent(ValueComponent::class.java, WORLD, 0, 0, 0) {
                it.value = 2
                throw failure
            }
        }

        assertSame(failure, actual)
        assertTrue(fixture.chunk().isDirty)
        fixture.manager.saveAll().join()
        assertEquals(2, fixture.savedValue())
    }

    @Test
    fun `replaced and removed instances cannot mark current data dirty`() {
        val fixture = Fixture()
        fixture.load()
        val old = fixture.put(1)
        val current = fixture.put(2)
        fixture.manager.saveAll().join()

        old.value = 3
        assertFalse(fixture.manager.markDirty(ValueComponent::class.java, WORLD, 0, 0, 0, old))
        assertFalse(fixture.chunk().isDirty)
        assertEquals(2, assertNotNull(fixture.current()).value)

        fixture.manager.removeComponent(ValueComponent::class.java, WORLD, 0, 0, 0)
        fixture.manager.saveAll().join()
        assertFalse(fixture.manager.markDirty(ValueComponent::class.java, WORLD, 0, 0, 0, current))
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `retained instances expire on unload and reload restores independent data`() {
        val fixture = Fixture()
        fixture.load()
        val old = fixture.put(1)
        assertNull(fixture.manager.unloadChunk(WORLD, 0, 0).join())
        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))

        old.value = 2
        assertFalse(fixture.manager.markDirty(ValueComponent::class.java, WORLD, 0, 0, 0, old))
        fixture.load()
        val current = assertNotNull(fixture.current())
        assertNotSame(old, current)
        assertEquals(1, current.value)
        assertFalse(fixture.manager.markDirty(ValueComponent::class.java, WORLD, 0, 0, 0, old))
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `completion of an older save does not acknowledge a later edit`() {
        val fixture = Fixture()
        fixture.load()
        fixture.put(1)
        val pending = CompletableFuture<Void>()
        fixture.backend.nextSave = pending
        val saving = fixture.manager.saveAll()
        assertFalse(saving.isDone)

        assertTrue(fixture.manager.editComponent(ValueComponent::class.java, WORLD, 0, 0, 0) {
            it.value = 2
        })
        pending.complete(null)
        saving.join()

        assertEquals(1, fixture.savedValue())
        assertTrue(fixture.chunk().isDirty)
        fixture.manager.saveAll().join()
        assertEquals(2, fixture.savedValue())
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `failed component writes are omitted and repaired components can be saved again`() {
        val fixture = Fixture()
        fixture.load()
        val component = fixture.put(1)
        component.writeFailure = IllegalStateException("Cannot serialize")

        fixture.manager.saveAll().join()

        assertTrue(fixture.backend.saves.single().citadels.single().components.isEmpty())
        assertFalse(fixture.chunk().isDirty)
        assertSame(component, fixture.current())
        component.writeFailure = null
        assertTrue(fixture.manager.markDirty(ValueComponent::class.java, WORLD, 0, 0, 0, component))
        fixture.manager.saveAll().join()
        assertEquals(1, fixture.savedValue())
    }

    @Test
    fun `failed component writes do not prevent unload or close from saving other components`() {
        for (close in listOf(false, true)) {
            val fixture = Fixture()
            fixture.manager.registerComponent("test", "other", OtherComponent::class.java)
            fixture.load()
            fixture.put(1).writeFailure = IllegalStateException("Cannot serialize")
            fixture.manager.putComponent(OtherComponent::class.java, WORLD, 0, 0, 0, OtherComponent())

            val saving = if (close) fixture.manager.closeAndSaveAll()
                else fixture.manager.unloadChunk(WORLD, 0, 0)
            assertNull(saving.join())

            assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))
            assertEquals(close, fixture.backend.closed)
            val restored = fixture.mapper.restore(fixture.backend.saves.single())
            val entity = assertNotNull(restored.getCitadel(0, 0, 0))
            val valueType = assertNotNull(fixture.types.getType(ValueComponent::class.java))
            val otherType = assertNotNull(fixture.types.getType(OtherComponent::class.java))
            assertNull(restored.getComponent(entity, valueType))
            assertNotNull(restored.getComponent(entity, otherType))
        }
    }

    @Test
    fun `close saves data and releases loaded chunks`() {
        val fixture = Fixture()
        fixture.load()
        fixture.put(42)

        assertNull(fixture.manager.closeAndSaveAll().join())

        assertTrue(fixture.backend.closed)
        assertEquals(42, fixture.savedValue())
        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))
        assertFailsWith<CompletionException> { fixture.manager.loadChunk(WORLD, 0, 0).join() }
    }

    private class Fixture {
        val types = TypeManager()
        val mapper = ChunkCordSnapshotMapper(types)
        val backend = MemoryBackend()
        val storage = ChunkBackendLifecycleCoordinator(backend, DirectScheduler, mapper, Executor(Runnable::run))
        val manager = SimpleComponentManager(storage, types)

        init {
            manager.registerComponent("test", "value", ValueComponent::class.java)
        }

        fun load() { manager.loadChunk(WORLD, 0, 0).join() }

        fun put(value: Int): ValueComponent = ValueComponent(value).also {
            manager.putComponent(ValueComponent::class.java, WORLD, 0, 0, 0, it)
        }

        fun current(): ValueComponent? = manager.getComponent(ValueComponent::class.java, WORLD, 0, 0, 0)

        fun chunk() = checkNotNull(storage.getIfChunkLoaded(WXZCord(WORLD, 0, 0)))

        fun savedValue(): Int {
            val restored = mapper.restore(backend.persisted.getValue(WXZCord(WORLD, 0, 0)))
            val entity = checkNotNull(restored.getCitadel(0, 0, 0))
            val type = checkNotNull(types.getType(ValueComponent::class.java))
            return checkNotNull(restored.getComponent(entity, type)).value
        }
    }

    private class MemoryBackend : DBStorage {
        val persisted = mutableMapOf<WXZCord, ChunkCordSnapshot>()
        val saves = mutableListOf<ChunkCordSnapshot>()
        var nextSave: CompletableFuture<Void>? = null
        var closed = false

        override fun loadChunk(location: WXZCord): CompletableFuture<ChunkCordSnapshot?> {
            return CompletableFuture.completedFuture(persisted[location])
        }

        override fun saveChunk(snapshot: ChunkCordSnapshot): CompletableFuture<Void> {
            saves.add(snapshot)
            val completion = nextSave ?: CompletableFuture.completedFuture<Void>(null)
            nextSave = null
            return completion.thenRun { persisted[snapshot.location] = snapshot }
        }

        override fun close(): CompletableFuture<Void> {
            closed = true
            return CompletableFuture.completedFuture(null)
        }
    }

    private object DirectScheduler : Scheduler {
        override fun <T> schedule(location: WXZCord, task: Callable<T>): CompletableFuture<T> {
            return try {
                CompletableFuture.completedFuture(task.call())
            } catch (exception: Exception) {
                CompletableFuture.failedFuture(exception)
            }
        }
    }

    private class ValueComponent(var value: Int = 0) : Component() {
        var writeFailure: RuntimeException? = null

        override fun write(output: DataOutput) {
            writeFailure?.let { throw it }
            output.writeInt(value)
        }

        override fun read(input: DataInput) {
            value = input.readInt()
        }
    }

    private class OtherComponent : Component() {
        override fun write(output: DataOutput) = Unit
        override fun read(input: DataInput) = Unit
    }

    private companion object {
        val WORLD = UUID(1L, 2L)
    }
}
