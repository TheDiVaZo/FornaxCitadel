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
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ObservationTargetIdentityTest {
    @Test
    fun `systems and viewers share one target through republication ticks and cleanup`() {
        Fixture().use { fixture ->
            val citadel = fixture.create()
            val first = RecordingSystem()
            val second = RecordingSystem()
            fixture.start(first, second)
            val alice = fixture.observe(citadel)
            val bob = fixture.observe(citadel, "bob", BOB)
            val shared = first.events.first().target
            fixture.scheduler.tick()

            val freshHandle = fixture.find()
            assertNotSame(citadel, freshHandle)
            assertEquals(citadel.runtimeId, freshHandle.runtimeId)
            alice.publisher.publish(listOf(freshHandle))
            bob.publisher.publish(listOf(fixture.find()))
            fixture.scheduler.tick()
            alice.subscription.close()
            assertTrue(fixture.service.unregisterSystem(first))
            bob.subscription.close()

            for (system in listOf(first, second)) {
                assertEquals(
                    listOf("enter:alice", "enter:bob", "tick:alice,bob", "tick:alice,bob",
                        "leave:alice", "leave:bob", "detach"),
                    system.events.map { it.label }
                )
                system.events.forEach { assertSame(shared, it.target, it.label) }
            }
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `component replacement retains the same target across restarted system callbacks`() {
        Fixture().use { fixture ->
            val citadel = fixture.create()
            val system = RecordingSystem()
            fixture.start(system)
            fixture.observe(citadel)
            val shared = system.events.single().target
            system.events.clear()

            fixture.manager.putComponent(ValueComponent::class.java, WORLD, citadel.x, citadel.y, citadel.z, ValueComponent())
            fixture.scheduler.tick()

            assertEquals(listOf("leave:alice", "detach", "enter:alice", "tick:alice"), system.events.map { it.label })
            system.events.forEach { assertSame(shared, it.target, it.label) }
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `replacement entity receives a new target while old cleanup retains the previous instance`() {
        Fixture().use { fixture ->
            val original = fixture.create()
            val system = RecordingSystem()
            fixture.start(system)
            val observer = fixture.observe(original)
            val oldTarget = system.events.single().target
            system.events.clear()

            assertTrue(fixture.manager.removeCitadel(original))
            val replacement = fixture.create()
            observer.publisher.publish(listOf(replacement))
            fixture.scheduler.tick()

            assertEquals(listOf("leave:alice", "detach", "enter:alice", "tick:alice"), system.events.map { it.label })
            assertSame(oldTarget, system.events[0].target)
            assertSame(oldTarget, system.events[1].target)
            val newTarget = system.events[2].target
            assertNotSame(oldTarget, newTarget)
            assertEquals(oldTarget.position, newTarget.position)
            assertTrue(oldTarget.runtimeId != newTarget.runtimeId)
            assertEquals(replacement.runtimeId, newTarget.runtimeId)
            assertSame(newTarget, system.events[3].target)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    private data class Event(val label: String, val target: ObservationTarget)

    private class RecordingSystem : ObservationSystem<String> {
        override val requiredComponents = setOf(ValueComponent::class.java)
        val events = mutableListOf<Event>()

        override fun onViewerEnter(citadel: CitadelHandle, target: ObservationTarget, viewer: String) {
            assertEquals(citadel.position, target.position)
            assertEquals(citadel.runtimeId, target.runtimeId)
            events.add(Event("enter:$viewer", target))
        }

        override fun tick(citadel: CitadelHandle, target: ObservationTarget, viewers: List<String>) {
            assertEquals(citadel.position, target.position)
            assertEquals(citadel.runtimeId, target.runtimeId)
            events.add(Event("tick:${viewers.joinToString(",")}", target))
        }

        override fun onViewerLeave(citadel: ObservationTarget, viewer: String) {
            events.add(Event("leave:$viewer", citadel))
        }

        override fun onDetach(citadel: ObservationTarget) {
            events.add(Event("detach", citadel))
        }
    }

    private data class Observer(val publisher: ObservationPublisher, val subscription: ObservationSubscription)

    private class Fixture : AutoCloseable {
        val scheduler = ManualScheduler()
        val manager = ComponentManager(MemoryStorage(), TypeManager())
        val failures = mutableListOf<Exception>()
        val service = ObservationService<String>(manager, scheduler, failureHandler = failures::add)

        init {
            manager.registerComponent("test", "observation_target_identity", ValueComponent::class.java)
        }

        fun create(): CitadelHandle {
            val draft = MutableCitadelDraft(CitadelPosition(WORLD, 2, 64, 3))
            draft.addComponent(ValueComponent::class.java, ValueComponent())
            manager.createCitadel(draft)
            return find()
        }

        fun find(): CitadelHandle = checkNotNull(manager.findCitadel(WORLD, 2, 64, 3))

        fun start(vararg systems: ObservationSystem<String>) {
            systems.forEach(service::registerSystem)
            service.enable()
        }

        fun observe(citadel: CitadelHandle, viewer: String = "alice", viewerId: UUID = ALICE): Observer {
            lateinit var publisher: ObservationPublisher
            val subscription = service.registerViewer(viewerId, viewer, Observation { _, sink ->
                publisher = sink
                sink.publish(listOf(citadel))
                ObservationSubscription {}
            })
            return Observer(publisher, subscription)
        }

        override fun close() {
            service.disable()
            manager.closeAndSaveAll().join()
        }
    }

    private class MemoryStorage : CitadelStorage {
        private val chunk = ChunkCord(WXZCord(WORLD, 0, 0))
        override fun getIfChunkLoaded(location: WXZCord): ChunkCord? = chunk.takeIf { it.location == location }
        override fun loadChunk(location: WXZCord): Nothing = error("Unexpected chunk load")
        override fun unloadChunk(location: WXZCord): Nothing = error("Unexpected chunk unload")
        override fun updateOpaque(chunk: ChunkCord): Nothing = error("Unexpected opaque update")
        override fun updateOpaque(key: Key): Nothing = error("Unexpected opaque update")
        override fun saveAll(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
        override fun closeAndSaveAll(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
    }

    private class ValueComponent : Component() {
        override fun read(input: DataInput) = Unit
        override fun write(output: DataOutput) = Unit
    }

    private companion object {
        val WORLD = UUID(111L, 112L)
        val ALICE = UUID(113L, 114L)
        val BOB = UUID(115L, 116L)
    }
}
