package ru.fornax.fornaxcitadel.core.systems.observation

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.scheduler.ManualScheduler
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.OpaqueBatchUpdateResult
import ru.fornax.fornaxcitadel.core.storage.OpaqueUpdateResult
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObservationRequirementsTest {
    @Test
    fun `every required type must be present before entering or ticking`() {
        Fixture().use { fixture ->
            val primaryOnly = fixture.create(secondary = null)
            val secondaryOnly = fixture.create(primary = null)
            val complete = fixture.create()
            val empty = fixture.create(primary = null, secondary = null)
            val system = RecordingSystem(BOTH)
            fixture.start(system)

            fixture.observe(listOf(primaryOnly, secondaryOnly, complete, empty))
            fixture.scheduler.tick()

            assertEquals(listOf("enter:${complete.x}:alice", "tick:${complete.x}"), system.events)
            assertEquals(listOf(complete.runtimeId), system.tickedIds)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `successful bindings stay independent across partial component matches`() {
        Fixture().use { fixture ->
            val first = fixture.create(primary = Primary(11), secondary = Secondary(12))
            val partial = fixture.create(primary = Primary(21), secondary = null)
            val last = fixture.create(primary = Primary(31), secondary = Secondary(32))
            val system = RecordingSystem(BOTH)
            fixture.start(system)

            fixture.observe(listOf(first, partial, last))
            assertEquals(listOf("enter:1:alice", "enter:3:alice"), system.events)
            system.events.clear()

            repeat(3) {
                fixture.scheduler.tick()
                assertEquals(listOf("tick:1", "tick:3"), system.events)
                system.events.clear()
            }

            fixture.put(partial, Secondary::class.java, Secondary(22))
            fixture.scheduler.tick()
            assertEquals(listOf("enter:2:alice", "tick:1", "tick:2", "tick:3"), system.events)
            system.events.clear()

            repeat(3) {
                fixture.scheduler.tick()
                assertEquals(listOf("tick:1", "tick:2", "tick:3"), system.events)
                system.events.clear()
            }
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `failed secondary reads recover without contaminating a queued observation`() {
        Fixture().use { fixture ->
            val first = fixture.create(primary = Primary(11), secondary = null)
            val second = fixture.create(primary = Primary(21), secondary = null)
            val system = RecordingSystem(linkedSetOf(Primary::class.java, LateSecondary::class.java))
            fixture.start(system)
            fixture.onFailure = { failure ->
                assertTrue(failure.cause is IllegalArgumentException)
                assertTrue(failure.message.orEmpty().contains(LateSecondary::class.java.name))
                fixture.onFailure = null
                fixture.manager.registerComponent(Key("test", "observation_late_secondary"), LateSecondary::class.java)
                fixture.put(first, LateSecondary::class.java, LateSecondary(12))
                fixture.put(second, LateSecondary::class.java, LateSecondary(22))
                fixture.observe(listOf(second), BOB, "bob")
                assertTrue(system.events.isEmpty(), "The queued observation must wait for the failed read to finish")
            }

            fixture.observe(listOf(first))
            assertEquals(1, fixture.failures.size)
            assertEquals(listOf("enter:2:bob"), system.events)
            system.events.clear()

            fixture.scheduler.tick()
            assertEquals(listOf("enter:1:alice", "tick:1", "tick:2"), system.events)
            system.events.clear()

            repeat(3) {
                fixture.scheduler.tick()
                assertEquals(listOf("tick:1", "tick:2"), system.events)
                system.events.clear()
            }
            assertEquals(1, fixture.failures.size)
        }
    }

    @Test
    fun `adding removing and restoring a secondary requirement updates the existing audience`() {
        Fixture().use { fixture ->
            val target = fixture.create(secondary = null)
            val system = RecordingSystem(BOTH)
            fixture.start(system)
            fixture.observe(listOf(target))
            fixture.scheduler.tick()
            assertTrue(system.events.isEmpty())

            fixture.put(target, Secondary::class.java, Secondary(1))
            fixture.scheduler.tick()
            assertEquals(listOf("enter:1:alice", "tick:1"), system.events)

            system.events.clear()
            fixture.remove(target, Secondary::class.java)
            fixture.scheduler.tick()
            assertEquals(listOf("leave:1:alice", "detach:1"), system.events)

            system.events.clear()
            fixture.put(target, Secondary::class.java, Secondary(2))
            fixture.scheduler.tick()
            assertEquals(listOf("enter:1:alice", "tick:1"), system.events)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `equal but distinct secondary instances restart the component set session`() {
        Fixture().use { fixture ->
            val old = Secondary(7)
            val replacement = Secondary(7)
            assertEquals(old, replacement)
            assertNotSame(old, replacement)
            val target = fixture.create(secondary = old)
            val system = RecordingSystem(BOTH)
            fixture.start(system)
            fixture.observe(listOf(target))
            system.events.clear()

            fixture.put(target, Secondary::class.java, replacement)
            fixture.scheduler.tick()

            assertEquals(listOf("leave:1:alice", "detach:1", "enter:1:alice", "tick:1"), system.events)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `field mutations and optional component changes do not restart the session`() {
        Fixture().use { fixture ->
            val target = fixture.create(extra = Extra(1))
            val system = RecordingSystem(BOTH)
            fixture.start(system)
            fixture.observe(listOf(target))
            system.events.clear()

            assertTrue(target.editComponent(Primary::class.java) { it.value++ })
            assertTrue(target.editComponent(Secondary::class.java) { it.value++ })
            fixture.scheduler.tick()
            fixture.put(target, Extra::class.java, Extra(2))
            fixture.scheduler.tick()
            fixture.remove(target, Extra::class.java)
            fixture.scheduler.tick()
            fixture.put(target, Extra::class.java, Extra(3))
            fixture.scheduler.tick()

            assertEquals(List(4) { "tick:1" }, system.events)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `empty requirements match componentless entities but still expire with their handles`() {
        Fixture().use { fixture ->
            val empty = fixture.create(primary = null, secondary = null)
            val complete = fixture.create()
            val system = RecordingSystem(emptySet())
            fixture.start(system)
            fixture.observe(listOf(empty, complete))
            fixture.scheduler.tick()
            assertEquals(listOf(empty.runtimeId, complete.runtimeId), system.tickedIds)
            system.events.clear()
            system.tickedIds.clear()

            assertTrue(fixture.manager.removeCitadel(empty))
            fixture.scheduler.tick()

            assertEquals(listOf("leave:1:alice", "detach:1", "tick:2"), system.events)
            assertEquals(listOf(complete.runtimeId), system.tickedIds)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `requirements are copied at registration and only reread after unregistering`() {
        Fixture().use { fixture ->
            val declared = linkedSetOf<Class<out Component>>(Primary::class.java)
            val target = fixture.create(secondary = null)
            val system = RecordingSystem(declared)
            fixture.start(system)
            declared.add(Secondary::class.java)

            fixture.observe(listOf(target))
            fixture.service.registerSystem(system) // Duplicate registration must not recapture requirements.
            fixture.scheduler.tick()
            assertEquals(listOf("enter:1:alice", "tick:1"), system.events)
            assertTrue(fixture.service.unregisterSystem(system))
            system.events.clear()

            fixture.service.registerSystem(system)
            fixture.scheduler.tick()
            assertTrue(system.events.isEmpty(), "The new registration requires both declared types")
            fixture.put(target, Secondary::class.java, Secondary())
            fixture.scheduler.tick()
            assertEquals(listOf("enter:1:alice", "tick:1"), system.events)
            system.events.clear()

            declared.clear()
            fixture.remove(target, Primary::class.java)
            fixture.scheduler.tick()
            assertEquals(listOf("leave:1:alice", "detach:1"), system.events)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `removing a secondary requirement during enter prevents later viewers from entering`() {
        Fixture().use { fixture ->
            val target = fixture.create()
            val system = RecordingSystem(BOTH).apply {
                onEnter = { citadel, viewer ->
                    assertEquals("alice", viewer)
                    fixture.remove(citadel, Secondary::class.java)
                }
            }
            fixture.service.enable()
            fixture.observe(listOf(target))
            fixture.observe(listOf(target), BOB, "bob")

            fixture.service.registerSystem(system)
            fixture.scheduler.tick()

            assertEquals(listOf("enter:1:alice", "leave:1:alice", "detach:1"), system.events)
            assertNull(target.getComponent(Secondary::class.java))
            assertTrue(system.tickedIds.isEmpty())
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `a preceding system can invalidate a secondary requirement before a later tick`() {
        Fixture().use { fixture ->
            val target = fixture.create()
            val mutating = RecordingSystem(setOf(Primary::class.java)).apply {
                onTick = { citadel -> fixture.remove(citadel, Secondary::class.java) }
            }
            val dependent = RecordingSystem(BOTH)
            fixture.service.registerSystem(mutating)
            fixture.start(dependent)
            fixture.observe(listOf(target))
            dependent.events.clear()

            fixture.scheduler.tick()

            assertEquals(listOf(target.runtimeId), mutating.tickedIds)
            assertTrue(dependent.tickedIds.isEmpty())
            assertEquals(listOf("leave:1:alice", "detach:1"), dependent.events)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    private class RecordingSystem(
        override val requiredComponents: Set<Class<out Component>>
    ) : ObservationSystem<String> {
        val events = mutableListOf<String>()
        val tickedIds = mutableListOf<Int>()
        var onEnter: ((CitadelHandle, String) -> Unit)? = null
        var onTick: ((CitadelHandle) -> Unit)? = null

        override fun onViewerEnter(citadel: CitadelHandle, target: ObservationTarget, viewer: String) {
            events.add("enter:${citadel.x}:$viewer")
            onEnter?.invoke(citadel, viewer)
        }

        override fun tick(citadel: CitadelHandle, target: ObservationTarget, viewers: List<String>) {
            events.add("tick:${citadel.x}")
            tickedIds.add(citadel.runtimeId)
            onTick?.invoke(citadel)
        }

        override fun onViewerLeave(citadel: ObservationTarget, viewer: String) {
            events.add("leave:${citadel.position.x}:$viewer")
        }

        override fun onDetach(citadel: ObservationTarget) {
            events.add("detach:${citadel.position.x}")
        }
    }

    private class Fixture : AutoCloseable {
        val scheduler = ManualScheduler()
        val manager = ComponentManager(MemoryStorage(), TypeManager())
        val failures = mutableListOf<Exception>()
        var onFailure: ((Exception) -> Unit)? = null
        val service = ObservationService<String>(manager, scheduler, failureHandler = { failure ->
            failures.add(failure)
            onFailure?.invoke(failure)
        })
        private var nextX = 1

        init {
            manager.registerComponent(Key("test", "observation_primary"), Primary::class.java)
            manager.registerComponent(Key("test", "observation_secondary"), Secondary::class.java)
            manager.registerComponent(Key("test", "observation_extra"), Extra::class.java)
            manager.loadChunk(WORLD, 0, 0).join()
        }

        fun create(primary: Primary? = Primary(), secondary: Secondary? = Secondary(), extra: Extra? = null): CitadelHandle {
            val x = nextX++
            val draft = MutableCitadelDraft(CitadelPosition(WORLD, x, 0, 0))
            primary?.let { draft.addComponent(Primary::class.java, it) }
            secondary?.let { draft.addComponent(Secondary::class.java, it) }
            extra?.let { draft.addComponent(Extra::class.java, it) }
            manager.createCitadel(draft)
            return checkNotNull(manager.findCitadel(WORLD, x, 0, 0))
        }

        fun <C : Component> put(target: CitadelHandle, type: Class<C>, component: C) {
            manager.putComponent(type, WORLD, target.x, target.y, target.z, component)
        }

        fun <C : Component> remove(target: CitadelHandle, type: Class<C>) {
            manager.removeComponent(type, WORLD, target.x, target.y, target.z)
        }

        fun start(system: ObservationSystem<String>) {
            service.registerSystem(system)
            service.enable()
        }

        fun observe(citadels: List<CitadelHandle>, viewerId: UUID = ALICE, viewer: String = "alice") {
            service.registerViewer(viewerId, viewer, Observation { _, publisher ->
                publisher.publish(citadels)
                ObservationSubscription {}
            })
        }

        override fun close() {
            service.disable()
            manager.closeAndSaveAll().join()
        }
    }

    private class MemoryStorage : CitadelStorage {
        private val chunks = mutableMapOf<WXZCord, ChunkCord>()
        override fun getIfChunkLoaded(location: WXZCord): ChunkCord? = chunks[location]
        override fun loadChunk(location: WXZCord): CompletableFuture<ChunkCord> =
            CompletableFuture.completedFuture(chunks.getOrPut(location) { ChunkCord(location) })
        override fun unloadChunk(location: WXZCord): CompletableFuture<Void> {
            chunks.remove(location)
            return CompletableFuture.completedFuture(null)
        }
        override fun updateOpaque(chunk: ChunkCord): CompletableFuture<OpaqueUpdateResult> =
            error("Observation does not decode components")
        override fun updateOpaque(key: Key): CompletableFuture<OpaqueBatchUpdateResult> =
            error("Observation does not decode components")
        override fun saveAll(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
        override fun closeAndSaveAll(): CompletableFuture<Void> {
            chunks.clear()
            return CompletableFuture.completedFuture(null)
        }
    }

    private data class Primary(var value: Int = 0) : Component() {
        override fun read(input: DataInput) { value = input.readInt() }
        override fun write(output: DataOutput) { output.writeInt(value) }
    }

    private data class Secondary(var value: Int = 0) : Component() {
        override fun read(input: DataInput) { value = input.readInt() }
        override fun write(output: DataOutput) { output.writeInt(value) }
    }

    private data class Extra(var value: Int = 0) : Component() {
        override fun read(input: DataInput) { value = input.readInt() }
        override fun write(output: DataOutput) { output.writeInt(value) }
    }

    private data class LateSecondary(var value: Int = 0) : Component() {
        override fun read(input: DataInput) { value = input.readInt() }
        override fun write(output: DataOutput) { output.writeInt(value) }
    }

    private companion object {
        val WORLD = UUID(91L, 92L)
        val ALICE = UUID(93L, 94L)
        val BOB = UUID(95L, 96L)
        val BOTH: Set<Class<out Component>> = linkedSetOf(Primary::class.java, Secondary::class.java)
    }
}
