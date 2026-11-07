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
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ObservationReentrancyTest {
    @Test
    fun `reregistering during refresh cleans every old target before entering it again`() {
        Fixture().use { fixture ->
            val first = fixture.create(1)
            val second = fixture.create(2)
            val resources = mutableSetOf<Int>()
            val events = mutableListOf<Pair<Int, String>>()
            var restartOnFirstDetach = false
            val system = object : ObservationSystem<String> {
                override val requiredComponents = setOf(ValueComponent::class.java)

                override fun onViewerEnter(citadel: CitadelHandle, target: ObservationTarget, viewer: String) {
                    assertTrue(resources.add(citadel.runtimeId), "New resources must follow the previous detach")
                    events.add(citadel.runtimeId to "enter")
                }

                override fun tick(citadel: CitadelHandle, target: ObservationTarget, viewers: List<String>) {
                    assertTrue(citadel.runtimeId in resources, "Old cleanup must not destroy the new session")
                }

                override fun onViewerLeave(citadel: ObservationTarget, viewer: String) {
                    events.add(citadel.runtimeId to "leave")
                }

                override fun onDetach(citadel: ObservationTarget) {
                    assertTrue(resources.remove(citadel.runtimeId), "Only an entered session may detach")
                    events.add(citadel.runtimeId to "detach")
                    if (restartOnFirstDetach && citadel.runtimeId == first.runtimeId) {
                        restartOnFirstDetach = false
                        assertTrue(fixture.service.unregisterSystem(this))
                        fixture.service.registerSystem(this)
                    }
                }
            }
            fixture.service.registerSystem(system)
            fixture.service.enable()
            fixture.observe(listOf(first, second))
            events.clear()
            restartOnFirstDetach = true
            fixture.manager.removeComponent(ValueComponent::class.java, WORLD, first.x, first.y, first.z)

            fixture.scheduler.tick()

            assertEquals(listOf("leave", "detach"), events.filter { it.first == first.runtimeId }.map { it.second })
            assertEquals(listOf("leave", "detach", "enter"), events.filter { it.first == second.runtimeId }.map { it.second })
            assertEquals(setOf(second.runtimeId), resources)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `replacing the component on every enter returns without phantom detach and retries later`() {
        Fixture().use { fixture ->
            val target = fixture.create(1)
            val resources = mutableSetOf<Int>()
            var enters = 0
            var leaves = 0
            var detaches = 0
            val system = object : ObservationSystem<String> {
                override val requiredComponents = setOf(ValueComponent::class.java)

                override fun onViewerEnter(citadel: CitadelHandle, target: ObservationTarget, viewer: String) {
                    val component = checkNotNull(citadel.getComponent(ValueComponent::class.java))
                    enters++
                    // Fail promptly if reconciliation regresses to an endless enter/replace loop.
                    assertTrue(enters < 16, "A finite callback must not cause unbounded synchronous reentry")
                    assertTrue(resources.add(citadel.runtimeId))
                    fixture.manager.putComponent(
                        ValueComponent::class.java, WORLD, citadel.x, citadel.y, citadel.z,
                        ValueComponent(component.value + 1)
                    )
                }

                override fun tick(citadel: CitadelHandle, target: ObservationTarget, viewers: List<String>) {
                    assertTrue(citadel.runtimeId in resources)
                }

                override fun onViewerLeave(citadel: ObservationTarget, viewer: String) {
                    assertTrue(citadel.runtimeId in resources)
                    leaves++
                }

                override fun onDetach(citadel: ObservationTarget) {
                    assertTrue(resources.remove(citadel.runtimeId), "No detach without an earlier enter")
                    detaches++
                }
            }
            fixture.service.registerSystem(system)
            fixture.service.enable()

            fixture.observe(listOf(target))

            assertEquals(1, enters)
            assertEquals(enters, leaves)
            assertEquals(enters, detaches)
            assertTrue(resources.isEmpty())
            fixture.scheduler.tick()
            assertTrue(enters > 1, "A deferred component session must be reconsidered later")
            assertEquals(enters, leaves)
            assertEquals(enters, detaches)
            assertTrue(resources.isEmpty())
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `recreating an observed entry retains its earlier dirty key position`() {
        Fixture().use { fixture ->
            val first = fixture.create(1)
            val second = fixture.create(2)
            val third = fixture.create(3)
            val replacementEnters = mutableListOf<Int>()
            val ticks = mutableListOf<Int>()
            lateinit var original: Observer
            var restart = false
            val system = object : ObservationSystem<String> {
                override val requiredComponents = setOf(ValueComponent::class.java)

                override fun onViewerEnter(citadel: CitadelHandle, target: ObservationTarget, viewer: String) {
                    if (viewer == "replacement") replacementEnters.add(citadel.runtimeId)
                }

                override fun tick(citadel: CitadelHandle, target: ObservationTarget, viewers: List<String>) {
                    assertEquals(listOf("replacement"), viewers)
                    ticks.add(citadel.runtimeId)
                }

                override fun onDetach(citadel: ObservationTarget) {
                    if (restart && citadel.runtimeId == first.runtimeId) {
                        restart = false
                        original.subscription.close()
                        fixture.observe(listOf(third, second), viewer = "replacement")
                    }
                }
            }
            fixture.service.registerSystem(system)
            fixture.service.enable()
            original = fixture.observe(listOf(first, second))
            restart = true
            fixture.manager.removeComponent(ValueComponent::class.java, WORLD, first.x, first.y, first.z)

            fixture.scheduler.tick()

            // The old second entry is removed before queued cleanup marks its absent key.
            // That earlier mark must survive republication, even though third is inserted first.
            assertEquals(listOf(second.runtimeId, third.runtimeId), replacementEnters)
            assertEquals(listOf(third.runtimeId, second.runtimeId), ticks)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `publications from earlier and later targets wait for the current refresh batch`() {
        Fixture().use { fixture ->
            val first = fixture.create(1)
            val second = fixture.create(2)
            fixture.service.enable()
            val alice = fixture.observe(listOf(first, second), viewer = "alice")
            val bob = fixture.observe(listOf(first, second), viewerId = UUID(85L, 86L), viewer = "bob")
            val events = mutableListOf<String>()
            val audiences = mutableListOf<List<String>>()
            val system = object : ObservationSystem<String> {
                override val requiredComponents = setOf(ValueComponent::class.java)

                override fun onViewerEnter(citadel: CitadelHandle, target: ObservationTarget, viewer: String) {
                    events.add("enter:${citadel.x}:$viewer")
                    if (viewer == "alice") {
                        if (citadel.runtimeId == first.runtimeId) {
                            alice.publisher.publish(listOf(second))
                            bob.publisher.publish(listOf(second))
                        } else {
                            alice.publisher.publish(listOf(first, second))
                        }
                    }
                }

                override fun onViewerLeave(citadel: ObservationTarget, viewer: String) {
                    events.add("leave:${citadel.position.x}:$viewer")
                }

                override fun tick(citadel: CitadelHandle, target: ObservationTarget, viewers: List<String>) {
                    audiences.add(viewers)
                }
            }

            fixture.service.registerSystem(system)
            fixture.scheduler.tick()

            assertEquals(
                listOf("enter:1:alice", "enter:1:bob", "enter:2:alice", "enter:2:bob", "leave:1:bob"),
                events
            )
            assertEquals(listOf(listOf("alice"), listOf("alice", "bob")), audiences)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `an escaped error preserves pending work and releases flags for the interrupted batch`() {
        Fixture().use { fixture ->
            val first = fixture.create(1)
            val second = fixture.create(2)
            val third = fixture.create(3)
            fixture.service.enable()
            val observer = fixture.observe(emptyList())
            val events = mutableListOf<String>()
            val failure = AssertionError("interrupt refresh")
            var replaceComponent = true
            var interrupt = true
            fixture.service.registerSystem(object : ObservationSystem<String> {
                override val requiredComponents = setOf(ValueComponent::class.java)

                override fun onViewerEnter(citadel: CitadelHandle, target: ObservationTarget, viewer: String) {
                    if (replaceComponent && citadel.runtimeId == first.runtimeId) {
                        replaceComponent = false
                        fixture.manager.putComponent(
                            ValueComponent::class.java, WORLD, citadel.x, citadel.y, citadel.z, ValueComponent(99)
                        )
                    }
                }

                override fun tick(citadel: CitadelHandle, target: ObservationTarget, viewers: List<String>) = Unit
            })
            fixture.service.registerSystem(object : ObservationSystem<String> {
                override val requiredComponents = setOf(ValueComponent::class.java)

                override fun onViewerEnter(citadel: CitadelHandle, target: ObservationTarget, viewer: String) {
                    events.add("enter:${citadel.x}")
                    if (interrupt) {
                        interrupt = false
                        observer.publisher.publish(listOf(third, second))
                        throw failure
                    }
                }

                override fun onViewerLeave(citadel: ObservationTarget, viewer: String) {
                    events.add("leave:${citadel.position.x}")
                }

                override fun onDetach(citadel: ObservationTarget) {
                    events.add("detach:${citadel.position.x}")
                }

                override fun tick(citadel: CitadelHandle, target: ObservationTarget, viewers: List<String>) {
                    events.add("tick:${citadel.x}")
                }
            })

            assertSame(failure, assertFailsWith<AssertionError> { observer.publisher.publish(listOf(first, second)) })
            assertEquals(listOf("enter:1"), events)

            fixture.scheduler.tick()

            assertEquals(
                listOf("enter:1", "leave:1", "detach:1", "enter:2", "enter:3", "tick:2", "tick:3"),
                events
            )
            events.clear()
            observer.publisher.publish(listOf(first, second, third))
            fixture.scheduler.tick()

            assertEquals(listOf("enter:1", "tick:2", "tick:3", "tick:1"), events)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    private data class Observer(val publisher: ObservationPublisher, val subscription: ObservationSubscription)

    private class Fixture : AutoCloseable {
        val scheduler = ManualScheduler()
        val manager = ComponentManager(MemoryStorage(), TypeManager())
        val failures = mutableListOf<Exception>()
        val service = ObservationService<String>(manager, scheduler, failureHandler = failures::add)

        init {
            manager.registerComponent(Key("test", "observation_reentrancy_value"), ValueComponent::class.java)
            manager.loadChunk(WORLD, 0, 0).join()
        }

        fun create(x: Int): CitadelHandle {
            val draft = MutableCitadelDraft(CitadelPosition(WORLD, x, 0, 0))
            draft.addComponent(ValueComponent::class.java, ValueComponent(x))
            manager.createCitadel(draft)
            return checkNotNull(manager.findCitadel(WORLD, x, 0, 0))
        }

        fun observe(
            citadels: List<CitadelHandle>, viewerId: UUID = VIEWER, viewer: String = "viewer"
        ): Observer {
            lateinit var publisher: ObservationPublisher
            val subscription = service.registerViewer(viewerId, viewer, Observation { _, output ->
                publisher = output
                output.publish(citadels)
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

    private class ValueComponent(val value: Int) : Component() {
        override fun read(input: DataInput) = error("Observation does not deserialize components")
        override fun write(output: DataOutput) { output.writeInt(value) }
    }

    private companion object {
        val WORLD = UUID(81L, 82L)
        val VIEWER = UUID(83L, 84L)
    }
}
