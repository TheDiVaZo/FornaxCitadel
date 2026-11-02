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
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ObservationServiceTest {
    @Test
    fun `overlapping snapshots tick once per matching citadel with its full audience`() {
        val fixture = Fixture()
        val first = fixture.create(1)
        val second = fixture.create(2)
        val withoutComponent = fixture.create(3, withComponent = false)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        val alice = fixture.observe(ALICE, "alice")
        val bob = fixture.observe(BOB, "bob")

        // Independently obtained handles for one entity must not duplicate a session.
        val anotherFirstHandle = fixture.find(1)
        alice.observation.publish(listOf(first, anotherFirstHandle, first, second, withoutComponent))
        bob.observation.publish(listOf(first))

        assertEquals(3, system.enters.size)
        assertTrue(system.ticks.isEmpty())
        fixture.scheduler.tick()

        assertEquals(2, system.ticks.size)
        assertEquals(setOf("alice", "bob"), system.ticks.single { it.handle.position == first.position }.viewers.toSet())
        assertEquals(listOf("alice"), system.ticks.single { it.handle.position == second.position }.viewers)
        assertTrue(system.ticks.all { it.handle.position != withoutComponent.position })
    }

    @Test
    fun `snapshot replacement leaves only missing viewers and detaches after the last one`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        val alice = fixture.observe(ALICE, "alice")
        val bob = fixture.observe(BOB, "bob")
        alice.observation.publish(listOf(target))
        bob.observation.publish(listOf(target))

        alice.observation.publish(emptyList())
        assertEquals(listOf("alice"), system.leaves.map { it.second })
        assertTrue(system.detaches.isEmpty())
        fixture.scheduler.tick()
        assertEquals(listOf("bob"), system.ticks.single().viewers)

        bob.subscription.close()
        bob.subscription.close()
        assertEquals(listOf("alice", "bob"), system.leaves.map { it.second })
        assertEquals(1, system.detaches.size)
        assertEquals(target.position, system.detaches.single().position)
        assertEquals(target.runtimeId, system.detaches.single().runtimeId)
        assertEquals(1, bob.observation.closeCalls)
        fixture.scheduler.tick()
        assertEquals(1, system.ticks.size)
    }

    @Test
    fun `published collections and tick audiences cannot mutate stored membership`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        val alice = fixture.observe(ALICE, "alice")
        val published = mutableListOf(target)
        alice.observation.publish(published)
        published.clear()

        fixture.scheduler.tick()
        val firstAudience = system.ticks.single().viewers
        assertEquals(listOf("alice"), firstAudience)
        assertFailsWith<UnsupportedOperationException> { (firstAudience as MutableList<String>).add("forged") }
        val bob = fixture.observe(BOB, "bob")
        bob.observation.publish(listOf(target))
        fixture.scheduler.tick()

        assertEquals(listOf("alice"), firstAudience)
        assertEquals(setOf("alice", "bob"), system.ticks.last().viewers.toSet())
    }

    @Test
    fun `publication is atomic when its iterator fails after yielding a new target`() {
        val fixture = Fixture()
        val original = fixture.create(1)
        val replacement = fixture.create(2)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        val alice = fixture.observe(ALICE, "alice", listOf(original))
        val failure = IllegalStateException("Snapshot iteration failed")
        val interrupted = sequence {
            yield(replacement)
            throw failure
        }.asIterable()

        assertSame(failure, assertFails { alice.observation.publish(interrupted) })

        assertEquals(listOf(original.position), system.enters.map { it.handle.position })
        assertTrue(system.leaves.isEmpty())
        assertTrue(system.detaches.isEmpty())
        fixture.scheduler.tick()
        assertEquals(listOf(original.position), system.ticks.map { it.handle.position })
        assertEquals(listOf("alice"), system.ticks.single().viewers)

        alice.observation.publish(listOf(replacement))
        fixture.scheduler.tick()
        assertEquals(replacement.position, system.ticks.last().handle.position)
        assertEquals(listOf(original.position), system.detaches.map { it.position })
    }

    @Test
    fun `stale and foreign handles are skipped without attaching replacements at their address`() {
        val fixture = Fixture()
        val removed = fixture.create(1)
        assertTrue(fixture.manager.removeCitadel(removed))
        val replacement = fixture.create(1)
        val foreign = Fixture().create(2)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        val alice = fixture.observe(ALICE, "alice")

        alice.observation.publish(listOf(removed, foreign))
        fixture.scheduler.tick()
        assertTrue(system.enters.isEmpty())
        assertTrue(system.ticks.isEmpty())

        alice.observation.publish(listOf(replacement))
        fixture.scheduler.tick()
        assertEquals(1, system.enters.size)
        assertEquals(replacement.runtimeId, system.ticks.single().handle.runtimeId)
    }

    @Test
    fun `removed entities are detached on the next pass without another publication`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        fixture.observe(ALICE, "alice").observation.publish(listOf(target))

        fixture.manager.removeCitadel(target)
        val replacement = fixture.create(1)
        fixture.scheduler.tick()

        assertEquals(1, system.leaves.size)
        assertEquals(target.runtimeId, system.leaves.single().first.runtimeId)
        assertEquals(1, system.detaches.size)
        assertTrue(system.ticks.isEmpty())
        assertEquals(1, system.enters.size)
        assertTrue(replacement.isCurrent)
    }

    @Test
    fun `chunk unload expires sessions even if the same position is loaded again`() {
        val fixture = Fixture()
        val original = fixture.create(1)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        fixture.observe(ALICE, "alice").observation.publish(listOf(original))

        fixture.manager.unloadChunk(WORLD, 0, 0).join()
        fixture.manager.loadChunk(WORLD, 0, 0).join()
        fixture.create(1)
        fixture.scheduler.tick()

        assertEquals(1, system.leaves.size)
        assertEquals(original.runtimeId, system.detaches.single().runtimeId)
        assertTrue(system.ticks.isEmpty())
        assertEquals(1, system.enters.size)
    }

    @Test
    fun `component replacement starts a new session for every viewer`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val original = checkNotNull(target.getComponent(ValueComponent::class.java))
        val replacement = ValueComponent(99)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        fixture.observe(ALICE, "alice").observation.publish(listOf(target))
        fixture.observe(BOB, "bob").observation.publish(listOf(target))

        fixture.manager.putComponent(ValueComponent::class.java, WORLD, 1, 0, 0, replacement)
        fixture.scheduler.tick()

        assertEquals(2, system.leaves.size)
        assertEquals(1, system.detaches.size)
        assertEquals(4, system.enters.size)
        assertTrue(system.enters.take(2).all { it.component === original })
        assertTrue(system.enters.takeLast(2).all { it.component === replacement })
        assertSame(replacement, system.ticks.single().component)
        assertEquals(setOf("alice", "bob"), system.ticks.single().viewers.toSet())
    }

    @Test
    fun `detach callback removing a replacement component creates no phantom session`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val original = checkNotNull(target.getComponent(ValueComponent::class.java))
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        fixture.observe(ALICE, "alice", listOf(target))
        system.onDetached = {
            fixture.manager.removeComponent(ValueComponent::class.java, WORLD, 1, 0, 0)
        }
        fixture.manager.putComponent(ValueComponent::class.java, WORLD, 1, 0, 0, ValueComponent(99))

        fixture.scheduler.tick()
        fixture.scheduler.tick()

        assertNull(target.getComponent(ValueComponent::class.java))
        assertEquals(1, system.enters.size)
        assertSame(original, system.enters.single().component)
        assertEquals(1, system.leaves.size)
        assertEquals(1, system.detaches.size)
        assertTrue(system.ticks.isEmpty())
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `removing a required component detaches while adding it later attaches again`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        fixture.observe(ALICE, "alice").observation.publish(listOf(target))

        fixture.manager.removeComponent(ValueComponent::class.java, WORLD, 1, 0, 0)
        fixture.scheduler.tick()
        assertEquals(1, system.leaves.size)
        assertEquals(1, system.detaches.size)
        assertTrue(system.ticks.isEmpty())

        val restored = ValueComponent(2)
        fixture.manager.putComponent(ValueComponent::class.java, WORLD, 1, 0, 0, restored)
        fixture.scheduler.tick()
        assertEquals(2, system.enters.size)
        assertSame(restored, system.ticks.single().component)
    }

    @Test
    fun `failed synchronous observation registration never commits its published targets`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        var failedPublisher: ObservationPublisher? = null
        val failure = IllegalStateException("Provider failed after publishing")
        val broken = Observation<String> { _, publisher ->
            failedPublisher = publisher
            publisher.publish(listOf(target))
            throw failure
        }

        assertSame(failure, assertFails { fixture.service.registerViewer(ALICE, "alice", broken) })
        assertTrue(system.enters.isEmpty())
        failedPublisher!!.publish(listOf(target))
        fixture.scheduler.tick()
        assertTrue(system.ticks.isEmpty())

        // A failed registration must release the UUID reservation as well as its snapshot.
        fixture.observe(ALICE, "retry", listOf(target))
        assertEquals(listOf("retry"), system.enters.map { it.viewer })
    }

    @Test
    fun `viewer identities are exclusive until close and old publishers stay inert`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        assertFails { fixture.service.registerViewer(ALICE, "alice", ControlledObservation()) }
        fixture.service.enable()
        val old = fixture.observe(ALICE, "old", listOf(target))
        assertFails { fixture.service.registerViewer(ALICE, "duplicate", ControlledObservation()) }

        old.subscription.close()
        val current = fixture.observe(ALICE, "new", listOf(target))
        old.observation.publish(emptyList())
        old.observation.publish(listOf(target))
        fixture.scheduler.tick()

        assertEquals(listOf("old", "new"), system.enters.map { it.viewer })
        assertEquals(listOf("old"), system.leaves.map { it.second })
        assertEquals(listOf("new"), system.ticks.single().viewers)
        assertEquals(0, current.observation.closeCalls)
    }

    @Test
    fun `disable closes all subscriptions and makes old timers inert after reenable`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        fixture.service.enable()
        assertEquals(1, fixture.scheduler.tasks.size)
        val oldTimer = fixture.scheduler.tasks.single()
        val old = fixture.observe(ALICE, "old", listOf(target))

        fixture.service.disable()
        fixture.service.disable()
        assertTrue(oldTimer.isCancelled)
        assertEquals(1, old.observation.closeCalls)
        assertEquals(1, system.leaves.size)
        assertEquals(1, system.detaches.size)
        fixture.service.enable()
        fixture.observe(ALICE, "new", listOf(target))
        old.observation.publish(listOf(target))
        oldTimer.callback.run()
        assertTrue(system.ticks.isEmpty())
        fixture.scheduler.tick()

        assertEquals(listOf("old", "new"), system.enters.map { it.viewer })
        assertEquals(listOf("new"), system.ticks.single().viewers)
    }

    @Test
    fun `restart inside tick detaches the old session before entering the replacement viewer`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val transitions = mutableListOf<String>()
        val system = RecordingSystem().apply {
            onEnter = { _, _, viewer -> transitions.add("enter:$viewer") }
            onLeave = { _, viewer -> transitions.add("leave:$viewer") }
            onDetached = { transitions.add("detach") }
        }
        fixture.service.registerSystem(system)
        fixture.service.enable()
        val oldTimer = fixture.scheduler.tasks.single()
        val old = fixture.observe(ALICE, "old", listOf(target))
        var replacement: Viewer? = null
        system.onTick = { _, _, viewers ->
            assertEquals(listOf("old"), viewers)
            system.onTick = null
            fixture.service.disable()
            fixture.service.enable()
            replacement = fixture.observe(ALICE, "new", listOf(target))
        }

        fixture.scheduler.tick()

        assertEquals(listOf("enter:old", "leave:old", "detach", "enter:new"), transitions)
        assertEquals(1, system.detaches.size)
        assertEquals(listOf(listOf("old")), system.ticks.map { it.viewers })
        assertTrue(oldTimer.isCancelled)
        assertEquals(1, old.observation.closeCalls)
        assertEquals(0, checkNotNull(replacement).observation.closeCalls)
        oldTimer.callback.run()
        old.observation.publish(listOf(target))
        assertEquals(1, system.ticks.size)

        fixture.scheduler.tick()

        assertEquals(listOf(listOf("old"), listOf("new")), system.ticks.map { it.viewers })
        assertEquals(1, system.detaches.size)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `unregistering a system releases only its sessions`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val first = RecordingSystem()
        val second = RecordingSystem()
        fixture.service.registerSystem(first)
        fixture.service.registerSystem(second)
        fixture.service.enable()
        val alice = fixture.observe(ALICE, "alice", listOf(target))

        assertTrue(fixture.service.unregisterSystem(first))
        assertFalse(fixture.service.unregisterSystem(first))
        assertEquals(1, first.leaves.size)
        assertEquals(1, first.detaches.size)
        assertTrue(second.leaves.isEmpty())
        assertEquals(0, alice.observation.closeCalls)
        fixture.scheduler.tick()
        assertTrue(first.ticks.isEmpty())
        assertEquals(1, second.ticks.size)

        fixture.service.registerSystem(first)
        assertEquals(2, first.enters.size)
    }

    @Test
    fun `system unregistering itself during tick releases only its own session`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val departing = RecordingSystem()
        val remaining = RecordingSystem()
        fixture.service.registerSystem(departing)
        fixture.service.registerSystem(remaining)
        fixture.service.enable()
        val alice = fixture.observe(ALICE, "alice", listOf(target))
        departing.onTick = { _, _, _ -> assertTrue(fixture.service.unregisterSystem(departing)) }

        fixture.scheduler.tick()

        assertEquals(1, departing.ticks.size)
        assertEquals(1, departing.leaves.size)
        assertEquals(1, departing.detaches.size)
        assertEquals(1, remaining.ticks.size)
        assertTrue(remaining.leaves.isEmpty())
        assertTrue(remaining.detaches.isEmpty())
        assertEquals(0, alice.observation.closeCalls)

        fixture.scheduler.tick()

        assertEquals(1, departing.ticks.size)
        assertEquals(2, remaining.ticks.size)
        assertEquals(listOf("alice"), remaining.ticks.last().viewers)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `callback failures do not skip other systems or interrupt cleanup`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val broken = RecordingSystem().apply {
            onEnter = { _, _, _ -> error("enter failed") }
            onTick = { _, _, _ -> error("tick failed") }
            onLeave = { _, _ -> error("leave failed") }
            onDetached = { error("detach failed") }
        }
        val healthy = RecordingSystem()
        fixture.service.registerSystem(broken)
        fixture.service.registerSystem(healthy)
        fixture.service.enable()
        fixture.observe(ALICE, "alice", listOf(target))
        fixture.scheduler.tick()

        assertEquals(1, healthy.enters.size)
        assertEquals(1, healthy.ticks.size)
        fixture.service.disable()
        assertEquals(1, healthy.leaves.size)
        assertEquals(1, healthy.detaches.size)
        assertEquals(setOf("enter failed", "tick failed", "leave failed", "detach failed"), fixture.failures.map { (it.cause ?: it).message }.toSet())
    }

    @Test
    fun `subscription close failures do not prevent closing remaining viewers`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        val alice = fixture.observe(ALICE, "alice", listOf(target))
        val bob = fixture.observe(BOB, "bob", listOf(target))
        alice.observation.closeFailure = IllegalStateException("close failed")

        fixture.service.disable()

        assertEquals(1, alice.observation.closeCalls)
        assertEquals(1, bob.observation.closeCalls)
        assertEquals(setOf("alice", "bob"), system.leaves.map { it.second }.toSet())
        assertEquals(1, system.detaches.size)
        assertEquals(listOf("close failed"), fixture.failures.map { (it.cause ?: it).message })
        alice.observation.publish(listOf(target))
        fixture.scheduler.tasks.single().callback.run()
        assertTrue(system.ticks.isEmpty())
    }

    @Test
    fun `closing a viewer inside enter defers leave until that callback has returned`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        val alice = fixture.observe(ALICE, "alice")
        var insideEnter = false
        var nestedLeave = false
        system.onEnter = { _, _, _ ->
            insideEnter = true
            alice.subscription.close()
            insideEnter = false
        }
        system.onLeave = { _, _ -> nestedLeave = insideEnter }

        alice.observation.publish(listOf(target))

        assertFalse(nestedLeave)
        assertEquals(1, system.enters.size)
        assertEquals(1, system.leaves.size)
        assertEquals(1, system.detaches.size)
        fixture.scheduler.tick()
        assertTrue(system.ticks.isEmpty())
    }

    @Test
    fun `publication during tick keeps the current audience stable and defers transitions`() {
        val fixture = Fixture()
        val target = fixture.create(1)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        val alice = fixture.observe(ALICE, "alice", listOf(target))
        var insideTick = false
        var nestedLeave = false
        system.onTick = { _, _, viewers ->
            insideTick = true
            alice.observation.publish(emptyList())
            assertEquals(listOf("alice"), viewers)
            insideTick = false
        }
        system.onLeave = { _, _ -> nestedLeave = insideTick }

        fixture.scheduler.tick()

        assertFalse(nestedLeave)
        assertEquals(1, system.ticks.size)
        assertEquals(1, system.leaves.size)
        assertEquals(1, system.detaches.size)
        fixture.scheduler.tick()
        assertEquals(1, system.ticks.size)
    }

    @Test
    fun `failed timer setup leaves service disabled and allows a clean retry`() {
        val fixture = Fixture()
        fixture.scheduler.timerFailure = IllegalStateException("timer failed")
        assertFails { fixture.service.enable() }
        assertFails { fixture.service.registerViewer(ALICE, "alice", ControlledObservation()) }

        fixture.scheduler.timerFailure = null
        fixture.service.enable()
        val alice = fixture.observe(ALICE, "alice")
        assertEquals(1, fixture.scheduler.tasks.size)
        fixture.service.disable()
        assertEquals(1, alice.observation.closeCalls)
    }

    @Test
    fun `configured interval controls both the first pass and repeating passes`() {
        val fixture = Fixture(intervalTicks = 3L)
        val target = fixture.create(1)
        val system = RecordingSystem()
        fixture.service.registerSystem(system)
        fixture.service.enable()
        fixture.observe(ALICE, "alice", listOf(target))
        val timer = fixture.scheduler.tasks.single()
        assertEquals(3L, timer.delayTicks)
        assertEquals(3L, timer.periodTicks)

        repeat(2) { fixture.scheduler.tick() }
        assertTrue(system.ticks.isEmpty())
        fixture.scheduler.tick()
        assertEquals(1, system.ticks.size)
        repeat(2) { fixture.scheduler.tick() }
        assertEquals(1, system.ticks.size)
        fixture.scheduler.tick()
        assertEquals(2, system.ticks.size)
    }

    private class Fixture(intervalTicks: Long = 1L) {
        val scheduler = ManualScheduler()
        private val storage = MemoryStorage()
        val manager = ComponentManager(storage, TypeManager())
        val failures = mutableListOf<Exception>()
        val service = ObservationService<String>(manager, scheduler, intervalTicks, failures::add)

        init {
            manager.registerComponent("test", "observation_value", ValueComponent::class.java)
            manager.loadChunk(WORLD, 0, 0).join()
        }

        fun create(x: Int, withComponent: Boolean = true): CitadelHandle {
            val draft = MutableCitadelDraft(CitadelPosition(WORLD, x, 0, 0))
            if (withComponent) draft.addComponent(ValueComponent::class.java, ValueComponent(x))
            manager.createCitadel(draft)
            return find(x)
        }

        fun find(x: Int): CitadelHandle = checkNotNull(manager.findCitadel(WORLD, x, 0, 0))

        fun observe(id: UUID, viewer: String, initial: List<CitadelHandle>? = null): Viewer {
            val observation = ControlledObservation(initial)
            return Viewer(observation, service.registerViewer(id, viewer, observation))
        }
    }

    private class ControlledObservation(private val initial: List<CitadelHandle>? = null) : Observation<String> {
        private lateinit var publisher: ObservationPublisher
        var closeCalls = 0
        var closeFailure: Exception? = null

        override fun observe(viewer: String, publisher: ObservationPublisher): ObservationSubscription {
            this.publisher = publisher
            initial?.let(publisher::publish)
            return ObservationSubscription {
                closeCalls++
                closeFailure?.let { throw it }
            }
        }

        fun publish(citadels: Iterable<CitadelHandle>) = publisher.publish(citadels)
    }

    private data class Viewer(val observation: ControlledObservation, val subscription: ObservationSubscription)
    private data class Enter(val handle: CitadelHandle, val component: ValueComponent, val viewer: String)
    private data class Tick(val handle: CitadelHandle, val component: ValueComponent, val viewers: List<String>)

    private class RecordingSystem : ObservationSystem<String> {
        override val requiredComponents = setOf(ValueComponent::class.java)
        val enters = mutableListOf<Enter>()
        val ticks = mutableListOf<Tick>()
        val leaves = mutableListOf<Pair<ObservationTarget, String>>()
        val detaches = mutableListOf<ObservationTarget>()
        var onEnter: ((CitadelHandle, ValueComponent, String) -> Unit)? = null
        var onTick: ((CitadelHandle, ValueComponent, List<String>) -> Unit)? = null
        var onLeave: ((ObservationTarget, String) -> Unit)? = null
        var onDetached: ((ObservationTarget) -> Unit)? = null

        override fun onViewerEnter(citadel: CitadelHandle, viewer: String) {
            val component = checkNotNull(citadel.getComponent(ValueComponent::class.java))
            enters.add(Enter(citadel, component, viewer))
            onEnter?.invoke(citadel, component, viewer)
        }

        override fun tick(citadel: CitadelHandle, viewers: List<String>) {
            val component = checkNotNull(citadel.getComponent(ValueComponent::class.java))
            ticks.add(Tick(citadel, component, viewers))
            onTick?.invoke(citadel, component, viewers)
        }

        override fun onViewerLeave(citadel: ObservationTarget, viewer: String) {
            leaves.add(citadel to viewer)
            onLeave?.invoke(citadel, viewer)
        }

        override fun onDetach(citadel: ObservationTarget) {
            detaches.add(citadel)
            onDetached?.invoke(citadel)
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
            error("Observation does not decode opaque components")

        override fun updateOpaque(key: Key): CompletableFuture<OpaqueBatchUpdateResult> =
            error("Observation does not decode opaque components")

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
        val WORLD = UUID(1L, 2L)
        val ALICE = UUID(3L, 4L)
        val BOB = UUID(5L, 6L)
    }
}
