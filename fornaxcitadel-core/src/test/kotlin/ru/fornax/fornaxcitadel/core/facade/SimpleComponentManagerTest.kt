package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.DBStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
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
    fun `draft creation publishes all components and consumes the draft`() {
        val fixture = Fixture()
        fixture.manager.registerComponent("test", "other", OtherComponent::class.java)
        fixture.manager.loadChunk(WORLD, -2, 1).join()
        val position = CitadelPosition(WORLD, -17, 319, 31)
        val value = ValueComponent(42)
        val other = OtherComponent()
        val draft = MutableCitadelDraft(position).apply {
            addComponent(ValueComponent::class.java, value)
            addComponent(OtherComponent::class.java, other)
        }
        assertNull(fixture.manager.findCitadel(WORLD, position.x, position.y, position.z))

        assertEquals(ActionResult.SUCCESS, fixture.manager.createCitadel(draft))

        val handle = assertNotNull(fixture.manager.findCitadel(WORLD, position.x, position.y, position.z))
        assertEquals(position, handle.position)
        assertTrue(handle.isCurrent)
        assertSame(value, handle.getComponent(ValueComponent::class.java))
        assertSame(other, handle.getComponent(OtherComponent::class.java))
        assertTrue(assertNotNull(fixture.storage.getIfChunkLoaded(WXZCord(WORLD, -2, 1))).isDirty)
        assertFailsWith<IllegalStateException> { draft.getComponent(ValueComponent::class.java) }
        assertFailsWith<IllegalStateException> { draft.addComponent(ValueComponent::class.java, ValueComponent()) }
        assertFailsWith<IllegalStateException> { fixture.manager.createCitadel(draft) }
    }

    @Test
    fun `draft validation rejects duplicate and mismatched component types without replacing data`() {
        val draft = MutableCitadelDraft(CitadelPosition(WORLD, 0, 0, 0))
        val original = ValueComponent(42)
        draft.addComponent(ValueComponent::class.java, original)

        assertFailsWith<IllegalArgumentException> {
            draft.addComponent(ValueComponent::class.java, ValueComponent(99))
        }
        @Suppress("UNCHECKED_CAST")
        val incorrectClass = OtherComponent::class.java as Class<Component>
        assertFailsWith<IllegalArgumentException> {
            draft.addComponent(incorrectClass, original)
        }
        assertSame(original, draft.getComponent(ValueComponent::class.java))
        assertNull(draft.getComponent(OtherComponent::class.java))
    }

    @Test
    fun `creation rejects unavailable positions and validates every component before mutation`() {
        val fixture = Fixture()
        val position = CitadelPosition(WORLD, 0, 0, 0)
        val draft = MutableCitadelDraft(position).apply {
            addComponent(ValueComponent::class.java, ValueComponent(42))
        }
        assertEquals(ActionResult.DENIED, fixture.manager.createCitadel(draft))
        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))
        fixture.load()

        draft.addComponent(OtherComponent::class.java, OtherComponent())
        assertFailsWith<IllegalArgumentException> { fixture.manager.createCitadel(draft) }
        assertTrue(fixture.chunk().isEmpty)
        assertFalse(fixture.chunk().isDirty)
        for (y in listOf(-65, 320)) {
            val invalidPosition = MutableCitadelDraft(CitadelPosition(WORLD, 0, y, 0))
            assertFailsWith<IllegalArgumentException> { fixture.manager.createCitadel(invalidPosition) }
        }
        assertTrue(fixture.chunk().isEmpty)
        assertFalse(fixture.chunk().isDirty)

        fixture.manager.registerComponent("test", "other", OtherComponent::class.java)
        assertEquals(ActionResult.SUCCESS, fixture.manager.createCitadel(draft))
        fixture.manager.saveAll().join()
        val handle = assertNotNull(fixture.manager.findCitadel(WORLD, 0, 0, 0))
        assertEquals(ActionResult.DENIED, fixture.manager.createCitadel(MutableCitadelDraft(position)))
        assertTrue(handle.isCurrent)
        assertEquals(42, assertNotNull(handle.getComponent(ValueComponent::class.java)).value)
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `put cannot implicitly create a position`() {
        val fixture = Fixture()
        fixture.load()

        assertFailsWith<IllegalStateException> {
            fixture.manager.putComponent(ValueComponent::class.java, WORLD, 0, 0, 0, ValueComponent())
        }
        assertTrue(fixture.chunk().isEmpty)
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `stale and foreign handles cannot access or remove a replacement entity`() {
        val fixture = Fixture()
        fixture.load()
        val original = fixture.put(1)
        val old = assertNotNull(fixture.manager.findCitadel(WORLD, 0, 0, 0))
        val foreignManager = SimpleComponentManager(fixture.storage, fixture.types)
        assertFalse(foreignManager.isCurrent(old))
        assertFalse(foreignManager.removeCitadel(old))
        assertTrue(old.isCurrent)

        assertTrue(fixture.manager.removeCitadel(old))
        val replacement = fixture.put(2)
        val current = assertNotNull(fixture.manager.findCitadel(WORLD, 0, 0, 0))
        fixture.manager.saveAll().join()

        assertFalse(old.isCurrent)
        assertNull(old.getComponent(ValueComponent::class.java))
        assertFalse(old.editComponent(ValueComponent::class.java) { error("Stale handle must not invoke callback") })
        assertFalse(old.markDirty(ValueComponent::class.java, original))
        assertFalse(fixture.manager.removeCitadel(old))
        assertTrue(current.isCurrent)
        assertSame(replacement, current.getComponent(ValueComponent::class.java))
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `handles expire on unload and cannot affect a restored entity`() {
        val fixture = Fixture()
        fixture.load()
        fixture.put(42)
        val old = assertNotNull(fixture.manager.findCitadel(WORLD, 0, 0, 0))
        fixture.manager.unloadChunk(WORLD, 0, 0).join()
        assertFalse(old.isCurrent)
        fixture.load()

        assertFalse(old.isCurrent)
        assertFalse(fixture.manager.removeCitadel(old))
        assertNull(old.getComponent(ValueComponent::class.java))
        val current = assertNotNull(fixture.manager.findCitadel(WORLD, 0, 0, 0))
        assertTrue(current.isCurrent)
        assertEquals(42, assertNotNull(current.getComponent(ValueComponent::class.java)).value)
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `handle edits mark partial mutations dirty when the callback fails`() {
        val fixture = Fixture()
        fixture.load()
        fixture.put(1)
        val handle = assertNotNull(fixture.manager.findCitadel(WORLD, 0, 0, 0))
        fixture.manager.saveAll().join()

        assertFailsWith<IllegalStateException> {
            handle.editComponent(ValueComponent::class.java) {
                it.value = 2
                error("Failed after mutation")
            }
        }

        assertTrue(fixture.chunk().isDirty)
        fixture.manager.saveAll().join()
        assertEquals(2, fixture.savedValue())
    }

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
        fixture.putComponent(ValueComponent::class.java, WORLD, -32, -64, 16, first)
        fixture.putComponent(ValueComponent::class.java, WORLD, -17, 319, 31, second)
        fixture.putComponent(OtherComponent::class.java, WORLD, -17, 319, 31, OtherComponent())
        fixture.putComponent(OtherComponent::class.java, WORLD, -30, 20, 19, OtherComponent())
        fixture.putComponent(ValueComponent::class.java, WORLD, -16, 0, 16, ValueComponent(3))
        fixture.putComponent(ValueComponent::class.java, otherWorld, -32, -64, 16, ValueComponent(4))
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
        fixture.putComponent(OtherComponent::class.java, WORLD, 0, 0, 0, OtherComponent())
        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, action)
    }

    @Test
    fun `height range iteration visits inclusive layers from the center with global coordinates and live components`() {
        val fixture = Fixture()
        val otherWorld = UUID(3L, 4L)
        fixture.manager.registerComponent("test", "other", OtherComponent::class.java)
        fixture.manager.loadChunk(WORLD, -2, 1).join()
        fixture.manager.loadChunk(WORLD, -1, 1).join()
        fixture.manager.loadChunk(otherWorld, -2, 1).join()
        val positions = listOf(
            Triple(-32, -38, 16),
            Triple(-17, -40, 31),
            Triple(-32, -42, 16),
            Triple(-32, -39, 16),
            Triple(-32, -40, 16),
            Triple(-32, -41, 16)
        )
        val components = positions.mapIndexed { index, position -> position to ValueComponent(index) }.toMap()
        for ((position, component) in components) {
            fixture.putComponent(ValueComponent::class.java, WORLD, position.first, position.second, position.third, component)
        }
        for (y in listOf(-43, -37)) {
            fixture.putComponent(ValueComponent::class.java, WORLD, -32, y, 16, ValueComponent(99))
        }
        fixture.putComponent(OtherComponent::class.java, WORLD, -32, -40, 16, OtherComponent())
        fixture.putComponent(OtherComponent::class.java, WORLD, -30, -40, 19, OtherComponent())
        fixture.putComponent(ValueComponent::class.java, WORLD, -16, -40, 16, ValueComponent(99))
        fixture.putComponent(ValueComponent::class.java, otherWorld, -32, -40, 16, ValueComponent(99))
        fixture.manager.saveAll().join()
        val chunk = assertNotNull(fixture.storage.getIfChunkLoaded(WXZCord(WORLD, -2, 1)))
        val visited = mutableListOf<Triple<Int, Int, Int>>()

        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, -2, 1, -40, 2) { citadelId, x, y, z, component ->
            val position = Triple(x, y, z)
            assertEquals(assertNotNull(chunk.getCitadel(x and 15, y, z and 15)).id, citadelId)
            assertSame(components.getValue(position), component)
            visited.add(position)
        }

        assertEquals(listOf(-40, -40, -41, -39, -42, -38), visited.map { it.second })
        assertEquals(components.keys, visited.toSet())
        assertFalse(chunk.isDirty)
    }

    @Test
    fun `zero height delta visits every matching citadel on the center layer once`() {
        val fixture = Fixture()
        fixture.load()
        val positions = listOf(Triple(0, 11, 0), Triple(0, 12, 0), Triple(15, 12, 15), Triple(0, 13, 0))
        for (position in positions) {
            fixture.putComponent(ValueComponent::class.java, WORLD, position.first, position.second, position.third, ValueComponent())
        }
        val visited = mutableListOf<Triple<Int, Int, Int>>()

        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, 12, 0) { _, x, y, z, _ ->
            visited.add(Triple(x, y, z))
        }

        assertEquals(2, visited.size)
        assertEquals(setOf(Triple(0, 12, 0), Triple(15, 12, 15)), visited.toSet())
    }

    @Test
    fun `height range iteration clips at world boundaries without losing valid layers`() {
        val fixture = Fixture()
        fixture.load()
        for (y in listOf(-64, -63, -62, -61, 316, 317, 318, 319)) {
            fixture.putComponent(ValueComponent::class.java, WORLD, 0, y, 0, ValueComponent())
        }

        for ((center, expected) in listOf(-64 to listOf(-64, -63, -62), 319 to listOf(319, 318, 317))) {
            val visited = mutableListOf<Int>()
            fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, center, 2) { _, _, y, _, _ ->
                visited.add(y)
            }

            assertEquals(expected, visited)
        }
    }

    @Test
    fun `maximum height delta visits the whole world without overflow and skips empty layers`() {
        val fixture = Fixture()
        fixture.load()
        for (y in listOf(-64, -1, 0, 1, 319)) {
            fixture.putComponent(ValueComponent::class.java, WORLD, 0, y, 0, ValueComponent())
        }

        val cases = listOf(
            -64 to listOf(-64, -1, 0, 1, 319),
            0 to listOf(0, -1, 1, -64, 319),
            319 to listOf(319, 1, 0, -1, -64)
        )
        for ((center, expected) in cases) {
            val visited = mutableListOf<Int>()
            fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, center, Int.MAX_VALUE) { _, _, y, _, _ ->
                visited.add(y)
            }

            assertEquals(expected, visited)
        }
    }

    @Test
    fun `height range iteration rejects invalid arguments even when the chunk is unloaded`() {
        for (loaded in listOf(false, true)) {
            val fixture = Fixture()
            if (loaded) fixture.load()
            val invalidArguments = listOf(
                -65 to 0,
                320 to 0,
                Int.MIN_VALUE to 0,
                Int.MAX_VALUE to 0,
                0 to -1,
                0 to Int.MIN_VALUE
            )

            for ((y, yDelta) in invalidArguments) {
                assertFailsWith<IllegalArgumentException> {
                    fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, y, yDelta) { _, _, _, _, _ ->
                        error("Invalid arguments must not invoke the callback")
                    }
                }
            }
            assertFailsWith<IllegalArgumentException> {
                fixture.manager.forEachComponentOfType(OtherComponent::class.java, WORLD, 0, 0, 0, 0) { _, _, _, _, _ ->
                    error("An unregistered type must not invoke the callback")
                }
            }
            assertEquals(loaded, fixture.manager.isChunkLoaded(WORLD, 0, 0))
        }
    }

    @Test
    fun `height range iteration skips unloaded and empty chunks and ranges without the selected type`() {
        val fixture = Fixture()
        val action: (Int, Int, Int, Int, ValueComponent) -> Unit = { _, _, _, _, _ ->
            error("No matching component should be visited")
        }

        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, 0, 2, action)
        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))
        fixture.load()
        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, 0, 2, action)
        fixture.manager.registerComponent("test", "other", OtherComponent::class.java)
        fixture.putComponent(OtherComponent::class.java, WORLD, 0, 0, 0, OtherComponent())
        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, 0, 2, action)
        fixture.putComponent(ValueComponent::class.java, WORLD, 0, 20, 0, ValueComponent())
        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, 0, 2, action)
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

            fixture.putComponent(ValueComponent::class.java, WORLD, x, y, z, component)

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
        fixture.putComponent(OtherComponent::class.java, WORLD, 0, 0, 0, other)
        fixture.putComponent(ValueComponent::class.java, otherWorld, 0, 0, 0, foreign)

        assertSame(first, fixture.putComponent(ValueComponent::class.java, WORLD, 0, 0, 0, replacement))
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
            fixture.putComponent(OtherComponent::class.java, WORLD, 0, 0, 0, OtherComponent())

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
            putComponent(ValueComponent::class.java, WORLD, 0, 0, 0, it)
        }

        fun <T : Component> putComponent(
            componentClass: Class<T>, world: UUID, x: Int, y: Int, z: Int, component: T
        ): T? {
            if (manager.findCitadel(world, x, y, z) == null) {
                check(manager.createCitadel(MutableCitadelDraft(CitadelPosition(world, x, y, z))) == ActionResult.SUCCESS)
            }
            return manager.putComponent(componentClass, world, x, y, z, component)
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
