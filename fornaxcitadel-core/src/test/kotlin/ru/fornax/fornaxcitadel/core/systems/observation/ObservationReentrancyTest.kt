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

                override fun onViewerEnter(citadel: CitadelHandle, viewer: String) {
                    assertTrue(resources.add(citadel.runtimeId), "New resources must follow the previous detach")
                    events.add(citadel.runtimeId to "enter")
                }

                override fun tick(citadel: CitadelHandle, viewers: List<String>) {
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

                override fun onViewerEnter(citadel: CitadelHandle, viewer: String) {
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

                override fun tick(citadel: CitadelHandle, viewers: List<String>) {
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

    private class Fixture : AutoCloseable {
        val scheduler = ManualScheduler()
        val manager = ComponentManager(MemoryStorage(), TypeManager())
        val failures = mutableListOf<Exception>()
        val service = ObservationService<String>(manager, scheduler, failureHandler = failures::add)

        init {
            manager.registerComponent("test", "observation_reentrancy_value", ValueComponent::class.java)
            manager.loadChunk(WORLD, 0, 0).join()
        }

        fun create(x: Int): CitadelHandle {
            val draft = MutableCitadelDraft(CitadelPosition(WORLD, x, 0, 0))
            draft.addComponent(ValueComponent::class.java, ValueComponent(x))
            manager.createCitadel(draft)
            return checkNotNull(manager.findCitadel(WORLD, x, 0, 0))
        }

        fun observe(citadels: List<CitadelHandle>) {
            service.registerViewer(VIEWER, "viewer", Observation { _, publisher ->
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

    private class ValueComponent(val value: Int) : Component() {
        override fun read(input: DataInput) = error("Observation does not deserialize components")
        override fun write(output: DataOutput) { output.writeInt(value) }
    }

    private companion object {
        val WORLD = UUID(81L, 82L)
        val VIEWER = UUID(83L, 84L)
    }
}
