package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.ReadableCitadelKind
import ru.fornax.fornaxcitadel.core.citadel.ReadableCitadelOwner
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.ReadableHealth
import ru.fornax.fornaxcitadel.modules.health.ReadableMaturing
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.ReadableComponent
import ru.fornax.fornaxcitadel.testing.ReadableTestValue
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.resolver.ExactPositionResolver
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.CitadelSnapshot
import ru.fornax.fornaxcitadel.core.storage.ComponentSnapshot
import ru.fornax.fornaxcitadel.core.storage.DBStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
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

class ComponentManagerTest {
    @Test
    fun `read facade returns live components without dirtying and observes edits`() {
        val fixture = Fixture()
        fixture.load()
        val original = fixture.put(10)
        val writable = assertNotNull(fixture.manager.findCitadel(WORLD, 0, 0, 0))
        val queries: ReadableCitadelFacade = CitadelFacade(fixture.manager, ExactPositionResolver)
        val reader: ReadableHandle = assertNotNull(queries.findProtecting(writable.position))
        fixture.manager.saveAll().join()

        val component: ReadableTestValue = assertNotNull(reader.getComponent(ValueComponent::class.java))
        val unknownType: Class<out Component<*>> = ValueComponent::class.java
        val unknown: ReadableComponent = assertNotNull(reader.getComponent(unknownType))
        assertSame(original, component)
        assertSame(component, unknown)
        assertEquals(writable.position, reader.position)
        assertEquals(writable.runtimeId, reader.runtimeId)
        assertTrue(reader.isCurrent)
        assertFalse(fixture.chunk().isDirty)

        assertTrue(writable.editComponent(ValueComponent::class.java) { it.value = 25 })

        assertEquals(25, component.value)
        assertSame(component, reader.getComponent(ValueComponent::class.java))
        assertTrue(fixture.chunk().isDirty)
        fixture.manager.saveAll().join()
        assertEquals(25, fixture.savedValue())
    }

    @Test
    fun `Java clients infer readable types and retain typed component mutations`() {
        val fixture = Fixture()
        fixture.load()
        fixture.manager.registerComponent(Health.KEY, Health::class.java)
        val health = Health(30.0)
        fixture.putComponent(Health::class.java, WORLD, 0, 0, 0, health)
        val writable = assertNotNull(fixture.manager.findCitadel(WORLD, 0, 0, 0))
        val reader: ReadableHandle = writable
        fixture.manager.saveAll().join()

        val component: ReadableHealth = assertNotNull(ReadonlyJavaClient.readHealth(reader))
        assertSame(health, component)
        assertSame(component, ReadonlyJavaClient.readUnknown(reader, Health::class.java))
        assertFalse(fixture.chunk().isDirty)

        assertTrue(ReadonlyJavaClient.editHealth(writable, 12.0))

        assertEquals(12.0, component.health)
        assertTrue(fixture.chunk().isDirty)

        val replacement = Health(20.0)
        assertSame(health, ReadonlyJavaClient.putHealth(writable, replacement))
        assertSame(replacement, ReadonlyJavaClient.readHealth(reader))
        assertSame(replacement, ReadonlyJavaClient.removeHealth(writable))
        assertNull(ReadonlyJavaClient.readHealth(reader))
        assertNull(ReadonlyJavaClient.putHealth(writable, health))
        assertSame(health, ReadonlyJavaClient.readHealth(reader))
    }

    @Test
    fun `read contracts do not expose mutations or serialization`() {
        val readTypes = listOf(
            ReadableComponent::class.java,
            ReadableHealth::class.java,
            ReadableCitadelOwner::class.java,
            ReadableCitadelKind::class.java,
            ReadableMaturing::class.java,
            ReadableHandle::class.java,
            ReadableCitadelFacade::class.java
        )
        val mutators = setOf(
            "setHealth", "setPlayerId", "setDefinitionKey", "setStartedAtMillis",
            "editComponent", "markDirty", "createCitadel", "removeCitadel", "putComponent", "removeComponent",
            "safeRead", "safeReadChecked", "safeWrite", "safeWriteChecked", "toByteArray"
        )
        for (type in readTypes) {
            assertTrue(type.methods.none { it.name in mutators }, "Read contract exposes a mutator: ${type.name}")
        }
        for (type in readTypes + listOf(CitadelFacade::class.java, CitadelHandle::class.java)) {
            assertTrue(type.methods.none { it.returnType == ComponentManager::class.java },
                "Public method exposes writable storage: ${type.name}")
            assertTrue(type.fields.none { it.type == ComponentManager::class.java },
                "Public field exposes writable storage: ${type.name}")
        }
        assertEquals(ReadableComponent::class.java,
            ReadableHandle::class.java.getMethod("getComponent", Class::class.java).returnType)
        assertEquals(ReadableHandle::class.java,
            ReadableCitadelFacade::class.java.getMethod("findProtecting", CitadelPosition::class.java).returnType)
    }

    @Test
    fun `draft creation publishes all components and consumes the draft`() {
        val fixture = Fixture()
        fixture.manager.registerComponent(Key("test", "other"), OtherComponent::class.java)
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
        val incorrectClass = OtherComponent::class.java as Class<Component<*>>
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

        fixture.manager.registerComponent(Key("test", "other"), OtherComponent::class.java)
        assertEquals(ActionResult.SUCCESS, fixture.manager.createCitadel(draft))
        fixture.manager.saveAll().join()
        val handle = assertNotNull(fixture.manager.findCitadel(WORLD, 0, 0, 0))
        assertEquals(ActionResult.DENIED, fixture.manager.createCitadel(MutableCitadelDraft(position)))
        assertTrue(handle.isCurrent)
        assertEquals(42, assertNotNull(handle.getComponent(ValueComponent::class.java)).value)
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `put cannot recreate a removed position`() {
        val fixture = Fixture()
        fixture.load()
        fixture.put(1)
        val removed = fixture.handle()
        assertTrue(fixture.manager.removeCitadel(removed))
        fixture.manager.saveAll().join()

        assertFailsWith<IllegalStateException> {
            removed.putComponent(ValueComponent::class.java, ValueComponent())
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
        fixture.manager.saveAll().join()
        val foreignManager = ComponentManager(fixture.storage, fixture.types)
        assertFalse(foreignManager.isCurrent(old))
        assertFalse(foreignManager.removeCitadel(old))
        assertFailsWith<IllegalStateException> {
            foreignManager.putComponent(old, ValueComponent::class.java, ValueComponent(99))
        }
        assertNull(foreignManager.removeComponent(old, ValueComponent::class.java))
        assertTrue(old.isCurrent)
        assertSame(original, old.getComponent(ValueComponent::class.java))
        assertFalse(fixture.chunk().isDirty)

        assertTrue(fixture.manager.removeCitadel(old))
        val replacement = fixture.put(2)
        val current = assertNotNull(fixture.manager.findCitadel(WORLD, 0, 0, 0))
        fixture.manager.saveAll().join()

        assertFalse(old.isCurrent)
        assertNull(old.getComponent(ValueComponent::class.java))
        assertFalse(old.editComponent(ValueComponent::class.java) { error("Stale handle must not invoke callback") })
        assertFalse(old.markDirty(ValueComponent::class.java, original))
        assertFailsWith<IllegalStateException> {
            old.putComponent(ValueComponent::class.java, ValueComponent(99))
        }
        assertNull(old.removeComponent(ValueComponent::class.java))
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
        assertFailsWith<IllegalStateException> {
            old.putComponent(ValueComponent::class.java, ValueComponent(99))
        }
        assertNull(old.removeComponent(ValueComponent::class.java))
        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))
        fixture.load()

        assertFalse(old.isCurrent)
        assertFalse(fixture.manager.removeCitadel(old))
        assertNull(old.getComponent(ValueComponent::class.java))
        assertFailsWith<IllegalStateException> {
            old.putComponent(ValueComponent::class.java, ValueComponent(99))
        }
        assertNull(old.removeComponent(ValueComponent::class.java))
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
    fun `failed handle edit after chunk reload dirties only the originally captured chunk`() {
        val fixture = Fixture()
        fixture.load()
        val original = fixture.put(1)
        val oldHandle = assertNotNull(fixture.manager.findCitadel(WORLD, 0, 0, 0))
        val originalChunk = fixture.chunk()
        fixture.manager.saveAll().join()
        assertFalse(originalChunk.isDirty)
        val failure = IllegalStateException("Failed after reloading and mutating the old component")

        val actual = assertFailsWith<IllegalStateException> {
            oldHandle.editComponent(ValueComponent::class.java) {
                assertSame(original, it)
                fixture.manager.unloadChunk(WORLD, 0, 0).join()
                fixture.load()
                it.value = 2
                throw failure
            }
        }

        val replacementChunk = fixture.chunk()
        val currentHandle = assertNotNull(fixture.manager.findCitadel(WORLD, 0, 0, 0))
        val current = assertNotNull(currentHandle.getComponent(ValueComponent::class.java))
        assertSame(failure, actual)
        assertNotSame(originalChunk, replacementChunk)
        assertTrue(originalChunk.isDirty)
        assertFalse(replacementChunk.isDirty)
        assertFalse(oldHandle.isCurrent)
        assertTrue(currentHandle.isCurrent)
        assertNotSame(original, current)
        assertEquals(2, original.value)
        assertEquals(1, current.value)
        assertEquals(1, fixture.savedValue())
    }

    @Test
    fun `iteration selects the type and chunk and exposes citadel ids and global coordinates with live components`() {
        val fixture = Fixture()
        val otherWorld = UUID(3L, 4L)
        fixture.manager.registerComponent(Key("test", "other"), OtherComponent::class.java)
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
        val visited = mutableListOf<Pair<Triple<Int, Int, Int>, ReadableTestValue>>()

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
        val action: (Int, Int, Int, Int, ReadableTestValue) -> Unit = { _, _, _, _, _ ->
            error("No matching component should be visited")
        }

        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, action)
        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))
        fixture.load()
        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, action)
        fixture.manager.registerComponent(Key("test", "other"), OtherComponent::class.java)
        fixture.putComponent(OtherComponent::class.java, WORLD, 0, 0, 0, OtherComponent())
        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, action)
    }

    @Test
    fun `height range iteration visits inclusive layers from the center with global coordinates and live components`() {
        val fixture = Fixture()
        val otherWorld = UUID(3L, 4L)
        fixture.manager.registerComponent(Key("test", "other"), OtherComponent::class.java)
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
        val action: (Int, Int, Int, Int, ReadableTestValue) -> Unit = { _, _, _, _, _ ->
            error("No matching component should be visited")
        }

        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, 0, 2, action)
        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))
        fixture.load()
        fixture.manager.forEachComponentOfType(ValueComponent::class.java, WORLD, 0, 0, 0, 2, action)
        fixture.manager.registerComponent(Key("test", "other"), OtherComponent::class.java)
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
            assertSame(component, fixture.handle(WORLD, x, y, z).getComponent(ValueComponent::class.java))
        }
    }

    @Test
    fun `put replace and remove preserve other components and worlds`() {
        val fixture = Fixture()
        val otherWorld = UUID(3L, 4L)
        fixture.load()
        fixture.manager.loadChunk(otherWorld, 0, 0).join()
        fixture.manager.registerComponent(Key("test", "other"), OtherComponent::class.java)
        val first = fixture.put(1)
        val replacement = ValueComponent(2)
        val other = OtherComponent()
        val foreign = ValueComponent(3)
        fixture.putComponent(OtherComponent::class.java, WORLD, 0, 0, 0, other)
        fixture.putComponent(ValueComponent::class.java, otherWorld, 0, 0, 0, foreign)
        val handle = fixture.handle()
        val foreignHandle = fixture.handle(otherWorld)
        fixture.manager.saveAll().join()

        assertSame(first, handle.putComponent(ValueComponent::class.java, replacement))
        assertSame(replacement, fixture.current())
        assertSame(other, handle.getComponent(OtherComponent::class.java))
        assertSame(foreign, foreignHandle.getComponent(ValueComponent::class.java))
        assertTrue(fixture.chunk().isDirty)
        assertFalse(assertNotNull(fixture.storage.getIfChunkLoaded(WXZCord(otherWorld, 0, 0))).isDirty)
        assertSame(replacement, handle.removeComponent(ValueComponent::class.java))
        assertNull(fixture.current())
        assertSame(other, handle.getComponent(OtherComponent::class.java))
        assertSame(foreign, foreignHandle.getComponent(ValueComponent::class.java))
        assertNull(handle.putComponent(ValueComponent::class.java, first))
        assertSame(first, handle.getComponent(ValueComponent::class.java))
        assertSame(other, handle.getComponent(OtherComponent::class.java))
        assertTrue(fixture.manager.removeCitadel(handle))
        assertNull(handle.getComponent(OtherComponent::class.java))
        assertFalse(fixture.manager.removeCitadel(handle))
    }

    @Test
    fun `missing data does not trigger loading or silently accept writes`() {
        val fixture = Fixture()
        assertNull(fixture.current())
        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))
        fixture.load()
        assertEquals(ActionResult.SUCCESS,
            fixture.manager.createCitadel(MutableCitadelDraft(CitadelPosition(WORLD, 0, 0, 0))))
        val handle = fixture.handle()
        fixture.manager.unloadChunk(WORLD, 0, 0).join()

        assertNull(handle.getComponent(ValueComponent::class.java))
        assertNull(handle.removeComponent(ValueComponent::class.java))
        assertFalse(fixture.manager.removeCitadel(handle))
        assertFalse(handle.editComponent(ValueComponent::class.java) {
            error("Missing component must not invoke the callback")
        })
        assertFalse(handle.markDirty(ValueComponent::class.java, ValueComponent()))
        assertFailsWith<IllegalStateException> {
            handle.putComponent(ValueComponent::class.java, ValueComponent(1))
        }
        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))

        fixture.load()
        assertTrue(fixture.manager.isChunkLoaded(WORLD, 0, 0))
        assertNull(fixture.current())
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `handle removes opaque component data without disturbing decoded neighbors`() {
        val fixture = Fixture()
        val otherKey = Key("test", "other")
        val valueKey = Key("test", "value")
        val location = WXZCord(WORLD, 0, 0)
        fixture.backend.persisted[location] = ChunkCordSnapshot(location, listOf(
            CitadelSnapshot(ChunkCord.packPosition(0, 0, 0), listOf(
                ComponentSnapshot(valueKey, ValueComponent(7).toByteArray()),
                ComponentSnapshot(otherKey, byteArrayOf())
            ))
        ))
        fixture.load()
        val handle = fixture.handle()
        val neighbor = assertNotNull(handle.getComponent(ValueComponent::class.java))
        fixture.manager.registerComponent(otherKey, OtherComponent::class.java)
        assertNull(handle.getComponent(OtherComponent::class.java))
        assertEquals(listOf(otherKey), fixture.chunk().captureOpaqueComponents().map { it.key })
        assertFalse(fixture.chunk().isDirty)

        assertNull(handle.removeComponent(OtherComponent::class.java))

        assertTrue(fixture.chunk().isDirty)
        assertTrue(fixture.chunk().captureOpaqueComponents().isEmpty())
        assertSame(neighbor, handle.getComponent(ValueComponent::class.java))
        fixture.manager.saveAll().join()
        assertEquals(listOf(valueKey), fixture.backend.persisted.getValue(location).citadels.single().components.map { it.key })
        assertNull(handle.removeComponent(OtherComponent::class.java))
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `unregistered component types are rejected without changing the entity`() {
        val fixture = Fixture()
        fixture.load()
        val original = fixture.put(1)
        val handle = fixture.handle()
        fixture.manager.saveAll().join()

        assertFailsWith<IllegalArgumentException> {
            handle.putComponent(OtherComponent::class.java, OtherComponent())
        }
        assertFailsWith<IllegalArgumentException> { handle.getComponent(OtherComponent::class.java) }
        assertFailsWith<IllegalArgumentException> { handle.removeComponent(OtherComponent::class.java) }
        fixture.manager.registerComponent(Key("test", "other"), OtherComponent::class.java)
        @Suppress("UNCHECKED_CAST")
        val incorrectClass = OtherComponent::class.java as Class<Component<*>>
        assertFailsWith<IllegalArgumentException> { handle.putComponent(incorrectClass, ValueComponent(99)) }
        assertNull(handle.getComponent(OtherComponent::class.java))
        assertSame(original, handle.getComponent(ValueComponent::class.java))
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `lookup rejects invalid coordinates without loading or changing chunks`() {
        for (loaded in listOf(false, true)) {
            val fixture = Fixture()
            if (loaded) fixture.load()
            for (y in listOf(-65, 320, Int.MIN_VALUE, Int.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> { fixture.manager.findCitadel(WORLD, 0, y, 0) }
            }
            for (coordinate in listOf((Short.MIN_VALUE.toInt() shl 4) - 1, (Short.MAX_VALUE.toInt() + 1) shl 4)) {
                assertFailsWith<IllegalArgumentException> { fixture.manager.findCitadel(WORLD, coordinate, 0, 0) }
                assertFailsWith<IllegalArgumentException> { fixture.manager.findCitadel(WORLD, 0, 0, coordinate) }
            }
            assertEquals(loaded, fixture.manager.isChunkLoaded(WORLD, 0, 0))
            if (loaded) {
                assertTrue(fixture.chunk().isEmpty)
                assertFalse(fixture.chunk().isDirty)
            }
        }
    }

    @Test
    fun `direct mutation is only saved after explicitly marking the live instance dirty`() {
        val fixture = Fixture()
        fixture.load()
        val component = fixture.put(1)
        fixture.manager.saveAll().join()
        assertSame(component, fixture.current())
        assertFalse(fixture.chunk().isDirty)

        component.value = 2
        fixture.manager.saveAll().join()

        assertEquals(1, fixture.backend.saves.size)
        assertEquals(1, fixture.savedValue())
        assertTrue(fixture.handle().markDirty(ValueComponent::class.java, component))
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
            fixture.handle().editComponent(ValueComponent::class.java) {
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
        assertFalse(fixture.handle().markDirty(ValueComponent::class.java, old))
        assertFalse(fixture.chunk().isDirty)
        assertEquals(2, assertNotNull(fixture.current()).value)

        fixture.handle().removeComponent(ValueComponent::class.java)
        fixture.manager.saveAll().join()
        assertFalse(fixture.handle().markDirty(ValueComponent::class.java, current))
        assertFalse(fixture.chunk().isDirty)
    }

    @Test
    fun `retained instances expire on unload and reload restores independent data`() {
        val fixture = Fixture()
        fixture.load()
        val old = fixture.put(1)
        val oldHandle = fixture.handle()
        assertNull(fixture.manager.unloadChunk(WORLD, 0, 0).join())
        assertFalse(fixture.manager.isChunkLoaded(WORLD, 0, 0))

        old.value = 2
        assertFalse(oldHandle.markDirty(ValueComponent::class.java, old))
        fixture.load()
        val current = assertNotNull(fixture.current())
        assertNotSame(old, current)
        assertEquals(1, current.value)
        assertFalse(oldHandle.markDirty(ValueComponent::class.java, old))
        assertFalse(fixture.handle().markDirty(ValueComponent::class.java, old))
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

        assertTrue(fixture.handle().editComponent(ValueComponent::class.java) {
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
        assertTrue(fixture.handle().markDirty(ValueComponent::class.java, component))
        fixture.manager.saveAll().join()
        assertEquals(1, fixture.savedValue())
    }

    @Test
    fun `failed component writes do not prevent unload or close from saving other components`() {
        for (close in listOf(false, true)) {
            val fixture = Fixture()
            fixture.manager.registerComponent(Key("test", "other"), OtherComponent::class.java)
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
        val manager = ComponentManager(storage, types)

        init {
            manager.registerComponent(Key("test", "value"), ValueComponent::class.java)
        }

        fun load() { manager.loadChunk(WORLD, 0, 0).join() }

        fun put(value: Int): ValueComponent = ValueComponent(value).also {
            putComponent(ValueComponent::class.java, WORLD, 0, 0, 0, it)
        }

        fun <T : Component<*>> putComponent(
            componentClass: Class<T>, world: UUID, x: Int, y: Int, z: Int, component: T
        ): T? {
            if (manager.findCitadel(world, x, y, z) == null) {
                check(manager.createCitadel(MutableCitadelDraft(CitadelPosition(world, x, y, z))) == ActionResult.SUCCESS)
            }
            return handle(world, x, y, z).putComponent(componentClass, component)
        }

        fun handle(world: UUID = WORLD, x: Int = 0, y: Int = 0, z: Int = 0): CitadelHandle =
            checkNotNull(manager.findCitadel(world, x, y, z))

        fun current(): ReadableTestValue? = manager.findCitadel(WORLD, 0, 0, 0)?.getComponent(ValueComponent::class.java)

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
        override fun runTaskTimer(task: Runnable, delayTicks: Long, periodTicks: Long): Nothing =
            error("Unexpected timer")

        override fun <T> runTask(location: WXZCord, task: Callable<T>): CompletableFuture<T> {
            return try {
                CompletableFuture.completedFuture(task.call())
            } catch (exception: Exception) {
                CompletableFuture.failedFuture(exception)
            }
        }
    }

    private class ValueComponent(override var value: Int = 0) : Component<ReadableTestValue>(), ReadableTestValue {
        override val readable: ReadableTestValue get() = this
        var writeFailure: RuntimeException? = null

        override fun write(output: DataOutput) {
            writeFailure?.let { throw it }
            output.writeInt(value)
        }

        override fun read(input: DataInput) {
            value = input.readInt()
        }
    }

    private class OtherComponent : Component<ReadableComponent>(), ReadableComponent {
        override val readable: ReadableComponent get() = this
        override fun write(output: DataOutput) = Unit
        override fun read(input: DataInput) = Unit
    }

    private companion object {
        val WORLD = UUID(1L, 2L)
    }
}
