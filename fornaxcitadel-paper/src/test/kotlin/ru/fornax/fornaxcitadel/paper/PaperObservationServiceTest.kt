package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.observation.Observation
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationPublisher
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationService
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationSubscription
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationSystem
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationTarget
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.paper.modules.observation.PaperObservationService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PaperObservationServiceTest {
    @Test
    fun `enable registers online viewers and join quit manage their own subscriptions`() {
        Fixture().use { fixture ->
            fixture.paper.stranger.online = false
            fixture.service.enable()
            fixture.service.enable()
            assertTrue(fixture.observations.isEnabled)
            assertEquals(listOf(fixture.paper.installer.id), fixture.source.sessions.map { it.viewer.uniqueId })
            assertEquals(1, fixture.paper.listenerRegistrations.size)
            assertEquals(1, fixture.scheduler.tasks.size)

            val joined = fixture.paper.player(UUID(41, 42), "joined")
            fixture.dispatch(PlayerJoinEvent(joined.player, Component.empty()))
            assertEquals(listOf(fixture.paper.installer.id, joined.id),
                fixture.source.sessions.map { it.viewer.uniqueId })

            fixture.dispatch(PlayerQuitEvent(joined.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))
            assertEquals(listOf(0, 1), fixture.source.sessions.map { it.closes })
            fixture.service.disable()
            fixture.service.disable()
            assertEquals(listOf(1, 1), fixture.source.sessions.map { it.closes })
            assertFalse(fixture.observations.isEnabled)
            assertTrue(fixture.scheduler.tasks.single().isCancelled)
            assertTrue(fixture.paper.listenerRegistrations.isEmpty())
        }
    }

    @Test
    fun `disable and enable create fresh viewer sessions from the current online set`() {
        Fixture().use { fixture ->
            fixture.service.enable()
            assertEquals(2, fixture.source.sessions.size)
            fixture.service.disable()
            assertTrue(fixture.source.sessions.all { it.closes == 1 })

            fixture.paper.installer.online = false
            fixture.service.enable()
            assertEquals(listOf(fixture.paper.installer.id, fixture.paper.stranger.id, fixture.paper.stranger.id),
                fixture.source.sessions.map { it.viewer.uniqueId })
            assertEquals(listOf(1, 1, 0), fixture.source.sessions.map { it.closes })
            assertEquals(1, fixture.paper.listenerRegistrations.size)
            assertEquals(1, fixture.scheduler.tasks.count { !it.isCancelled })
        }
    }

    @Test
    fun `enable from viewer leave callback survives the previous disable completing`() {
        Fixture().use { fixture ->
            fixture.paper.stranger.online = false
            fixture.paper.place()
            val position = fixture.paper.position
            fixture.source.initial = listOf(assertNotNull(fixture.paper.manager.findCitadel(
                position.world, position.x, position.y, position.z)))
            var restarted = false
            val audiences = mutableListOf<List<UUID>>()
            fixture.observations.registerSystem(object : ObservationSystem<Player> {
                override val requiredComponents = setOf(Health::class.java)

                override fun onViewerLeave(citadel: ObservationTarget, viewer: Player) {
                    if (!restarted) {
                        restarted = true
                        fixture.service.enable()
                    }
                }

                override fun tick(citadel: CitadelHandle, viewers: List<Player>) {
                    audiences.add(viewers.map { it.uniqueId })
                }
            })
            fixture.service.enable()

            fixture.service.disable()

            assertTrue(restarted)
            assertTrue(fixture.observations.isEnabled)
            assertEquals(listOf(1, 0), fixture.source.sessions.map { it.closes })
            assertEquals(1, fixture.paper.listenerRegistrations.size)
            assertEquals(1, fixture.scheduler.tasks.count { !it.isCancelled })
            fixture.scheduler.tasks.last().run()
            assertEquals(listOf(listOf(fixture.paper.installer.id)), audiences)
        }
    }

    @Test
    fun `failed viewer startup rolls back earlier subscriptions timer and listener`() {
        Fixture().use { fixture ->
            fixture.source.reject = fixture.paper.stranger.id
            assertFailsWith<IllegalStateException> { fixture.service.enable() }
            assertFalse(fixture.observations.isEnabled)
            assertTrue(fixture.paper.listenerRegistrations.isEmpty())
            assertTrue(fixture.scheduler.tasks.all { it.isCancelled })
            assertEquals(1, fixture.source.sessions.single().closes)

            fixture.source.reject = null
            fixture.service.enable()
            assertTrue(fixture.observations.isEnabled)
            assertEquals(2, fixture.source.sessions.count { it.closes == 0 })
            assertEquals(1, fixture.paper.listenerRegistrations.size)
        }
    }

    @Test
    fun `listener registration failure releases observation timer and allows retry`() {
        Fixture().use { fixture ->
            fixture.paper.listenerRegistrationFailure = IllegalStateException("registration failed")
            assertFailsWith<IllegalStateException> { fixture.service.enable() }
            assertFalse(fixture.observations.isEnabled)
            assertTrue(fixture.paper.listenerRegistrations.isEmpty())
            assertTrue(fixture.scheduler.tasks.all { it.isCancelled })
            assertTrue(fixture.source.sessions.all { it.closes == 1 })

            fixture.paper.listenerRegistrationFailure = null
            fixture.service.enable()
            assertTrue(fixture.observations.isEnabled)
            assertEquals(2, fixture.source.sessions.count { it.closes == 0 })
        }
    }

    private class Fixture : AutoCloseable {
        val paper = PaperTestFixture(initializeSystems = false, trackListenerLifecycle = true)
        val scheduler = ManualScheduler()
        val observations = ObservationService<Player>(paper.manager, scheduler)
        val source = RecordingObservation()
        val service = PaperObservationService(paper.plugin, observations, source)

        fun dispatch(event: Event) {
            val listener = paper.listenerRegistrations.map { it.first }.single { listener ->
                listener.javaClass.methods.any {
                    it.isAnnotationPresent(EventHandler::class.java) &&
                        it.parameterTypes.contentEquals(arrayOf(event.javaClass))
                }
            }
            dispatch(listener, event, paper.plugin)
        }

        override fun close() {
            service.disable()
            paper.close()
        }
    }

    private class RecordingObservation : Observation<Player> {
        val sessions = mutableListOf<Session>()
        var initial = emptyList<CitadelHandle>()
        var reject: UUID? = null

        override fun observe(viewer: Player, publisher: ObservationPublisher): ObservationSubscription {
            check(viewer.uniqueId != reject) { "viewer startup failed" }
            val session = Session(viewer).also(sessions::add)
            publisher.publish(initial)
            return ObservationSubscription { session.closes++ }
        }
    }

    private class Session(val viewer: Player, var closes: Int = 0)
}
