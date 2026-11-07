package ru.fornax.fornaxcitadel.paper

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.observation.Observation
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationService
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationSubscription
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamagePolicy
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.modules.hpdisplay.CrackHealthObservationSystem
import ru.fornax.fornaxcitadel.paper.scheduler.PaperScheduler
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperCrackHealthObservationSystemTest {
    @Test
    fun `entry sends immediately while unchanged stage waits twenty server ticks`() {
        Fixture().use { fixture ->
            fixture.observe(fixture.paper.installer)
            val packets = fixture.paper.installer.blockDamagePackets
            assertEquals(1, packets.size)
            assertEquals(0.5f, packets.single().progress)
            assertEquals(-fixture.target.runtimeId, packets.single().sourceId)
            assertEquals(fixture.paper.position, packets.single().position())

            fixture.paper.advanceTicks(19)
            assertEquals(1, packets.size)
            fixture.paper.advanceTicks(1)
            assertEquals(2, packets.size)
            assertEquals(packets[0], packets[1])

            fixture.health(49.0) // 0.50 and 0.51 both map to protocol stage 4.
            fixture.paper.advanceTicks(1)
            assertEquals(2, packets.size)
            fixture.health(25.0)
            fixture.paper.advanceTicks(1)
            assertEquals(3, packets.size)
            assertEquals(0.75f, packets.last().progress)
        }
    }

    @Test
    fun `full health clears a displayed overlay and does not keep resending clears`() {
        Fixture().use { fixture ->
            fixture.observe(fixture.paper.installer)
            fixture.health(100.0)
            fixture.paper.advanceTicks(1)

            val packets = fixture.paper.installer.blockDamagePackets
            assertEquals(listOf(0.5f, 0f), packets.map { it.progress })
            assertEquals(-fixture.target.runtimeId, packets.last().sourceId)
            fixture.paper.advanceTicks(40)
            assertEquals(2, packets.size)

            fixture.health(0.0)
            fixture.paper.advanceTicks(1)
            assertEquals(0.99f, packets.last().progress)
            assertTrue(packets.last().progress < 1f)
        }
    }

    @Test
    fun `stored health changes are displayed consistently to the entire audience`() {
        Fixture().use { fixture ->
            fixture.observe(fixture.paper.installer)
            fixture.observe(fixture.paper.stranger)
            fixture.health(25.0)

            fixture.paper.advanceTicks(1)

            assertEquals(0.75f, fixture.paper.installer.blockDamagePackets.last().progress)
            assertEquals(0.75f, fixture.paper.stranger.blockDamagePackets.last().progress)
            assertEquals(1, fixture.paper.scheduledTasks.count { !it.cancelled && it.repeating })
            assertEquals(0, fixture.paper.blockLookups)
            assertEquals(0, fixture.paper.vanillaBlockChanges)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `every health bearing type is displayed with an independent negative source id`() {
        Fixture().use { fixture ->
            val protectedType = Key("test", "protected_health")
            fixture.paper.module.registerDefinition(CitadelDefinition(protectedType, listOf(initialHealth(75.0))),
                DamagePolicy.DENY)
            val protected = fixture.create(2, type = protectedType)
            val emptyType = Key("test", "without_health")
            fixture.paper.module.registerDefinition(CitadelDefinition(emptyType, emptyList()), DamagePolicy.ALLOW)
            val withoutHealth = fixture.create(3, type = emptyType)

            fixture.observe(fixture.paper.installer, listOf(fixture.target, protected, withoutHealth))

            val packets = fixture.paper.installer.blockDamagePackets
            assertEquals(setOf(Health::class.java), fixture.system.requiredComponents)
            assertEquals(setOf(-fixture.target.runtimeId, -protected.runtimeId), packets.map { it.sourceId }.toSet())
            assertTrue(packets.all { it.sourceId < 0 })
            assertEquals(0.25f, packets.single { it.sourceId == -protected.runtimeId }.progress)
            assertEquals(2, packets.size)
        }
    }

    @Test
    fun `unsent chunks are retried without loading chunks and negative coordinates use their own key`() {
        Fixture().use { fixture ->
            val target = fixture.create(-1, z = -17)
            val viewer = fixture.paper.installer
            fixture.observe(viewer, listOf(target))
            fixture.paper.advanceTicks(1)
            assertTrue(viewer.blockDamagePackets.isEmpty())

            viewer.sentChunks.add(Chunk.getChunkKey(-1, -2))
            fixture.paper.advanceTicks(1)

            assertEquals(target.position, viewer.blockDamagePackets.single().position())
            assertEquals(-target.runtimeId, viewer.blockDamagePackets.single().sourceId)
            assertEquals(0, fixture.paper.blockLookups)
        }
    }

    @Test
    fun `offline and other world viewers receive no overlay until eligible`() {
        Fixture().use { fixture ->
            fixture.paper.installer.online = false
            fixture.paper.stranger.location = Location(otherWorld(), 1.0, 64.0, 2.0)
            fixture.observe(fixture.paper.installer)
            fixture.observe(fixture.paper.stranger)
            fixture.paper.advanceTicks(1)
            assertTrue(fixture.paper.installer.blockDamagePackets.isEmpty())
            assertTrue(fixture.paper.stranger.blockDamagePackets.isEmpty())

            fixture.paper.installer.online = true
            fixture.paper.stranger.location = Location(fixture.paper.world, 1.0, 64.0, 2.0)
            fixture.paper.advanceTicks(1)
            assertEquals(0.5f, fixture.paper.installer.blockDamagePackets.single().progress)
            assertEquals(0.5f, fixture.paper.stranger.blockDamagePackets.single().progress)
        }
    }

    @Test
    fun `viewer departure clears only its own overlay even after a world change`() {
        Fixture().use { fixture ->
            val first = fixture.observe(fixture.paper.installer)
            fixture.observe(fixture.paper.stranger)
            val newWorld = otherWorld()
            fixture.paper.installer.location = Location(newWorld, 12.0, 80.0, 18.0)

            first.close()
            first.close()

            val packets = fixture.paper.installer.blockDamagePackets
            assertEquals(listOf(0.5f, 0f), packets.map { it.progress })
            assertEquals(-fixture.target.runtimeId, packets.last().sourceId)
            assertSame(newWorld, packets.last().location.world)
            assertEquals(1, fixture.paper.stranger.blockDamagePackets.size)
            fixture.paper.advanceTicks(1)
            assertEquals(2, packets.size)
        }
    }

    @Test
    fun `component replacement clears the previous display before entering its new health`() {
        Fixture().use { fixture ->
            fixture.observe(fixture.paper.installer)
            val position = fixture.target.position
            fixture.paper.manager.putComponent(Health::class.java, position.world, position.x, position.y,
                position.z, ReplacementHealth(100.0, 75.0))

            fixture.paper.advanceTicks(1)

            val packets = fixture.paper.installer.blockDamagePackets
            assertEquals(listOf(0.5f, 0f, 0.25f), packets.map { it.progress })
            assertTrue(packets.all { it.sourceId == -fixture.target.runtimeId })
            fixture.paper.manager.removeComponent(Health::class.java, position.world, position.x, position.y, position.z)
            fixture.paper.advanceTicks(1)
            assertEquals(listOf(0.5f, 0f, 0.25f, 0f), packets.map { it.progress })
            fixture.observations.disable()
            assertEquals(4, packets.size)
        }
    }

    @Test
    fun `destroyed target is cleared using retained identity after its handle expires`() {
        Fixture().use { fixture ->
            fixture.observe(fixture.paper.installer)
            assertEquals(ActionResult.SUCCESS, fixture.paper.module.health.damage(fixture.target, 50.0))
            assertFalse(fixture.target.isCurrent)

            fixture.paper.advanceTicks(1)

            val packets = fixture.paper.installer.blockDamagePackets
            assertEquals(listOf(0.5f, 0f), packets.map { it.progress })
            assertEquals(-fixture.target.runtimeId, packets.last().sourceId)
            assertEquals(fixture.target.position, packets.last().position())
            assertEquals(0, fixture.paper.vanillaBlockChanges)
        }
    }

    @Test
    fun `disable clears every viewer and a replacement at the same position gets a new source`() {
        Fixture().use { fixture ->
            fixture.observe(fixture.paper.installer)
            fixture.observe(fixture.paper.stranger)
            val oldTimer = fixture.paper.scheduledTasks.single { it.repeating && !it.cancelled }

            fixture.observations.disable()
            fixture.observations.disable()

            assertEquals(listOf(0.5f, 0f), fixture.paper.installer.blockDamagePackets.map { it.progress })
            assertEquals(listOf(0.5f, 0f), fixture.paper.stranger.blockDamagePackets.map { it.progress })
            assertTrue(oldTimer.cancelled)
            assertTrue(fixture.paper.manager.removeCitadel(fixture.target))
            val replacement = fixture.create(fixture.target.x)
            fixture.observations.enable()
            fixture.observe(fixture.paper.installer, listOf(replacement))
            val packets = fixture.paper.installer.blockDamagePackets
            assertEquals(-replacement.runtimeId, packets.last().sourceId)
            assertNotEquals(packets.first().sourceId, packets.last().sourceId)
            oldTimer.action.run()
            assertEquals(3, packets.size)
        }
    }

    @Test
    fun `failed send does not block another viewer and both damage and clear are retried`() {
        Fixture().use { fixture ->
            val first = fixture.paper.installer
            val second = fixture.paper.stranger
            fixture.observe(first)
            fixture.observe(second)
            first.blockDamageFailure = IllegalStateException("send failed")
            fixture.health(25.0)

            fixture.paper.advanceTicks(1)
            assertEquals(listOf(0.5f), first.blockDamagePackets.map { it.progress })
            assertEquals(listOf(0.5f, 0.75f), second.blockDamagePackets.map { it.progress })
            assertEquals(2, first.blockDamageAttempts)

            first.blockDamageFailure = null
            fixture.paper.advanceTicks(1)
            assertEquals(listOf(0.5f, 0.75f), first.blockDamagePackets.map { it.progress })
            assertEquals(2, second.blockDamagePackets.size)

            first.blockDamageFailure = IllegalStateException("clear failed")
            fixture.health(100.0)
            fixture.paper.advanceTicks(1)
            assertEquals(0f, second.blockDamagePackets.last().progress)
            assertEquals(0.75f, first.blockDamagePackets.last().progress)
            first.blockDamageFailure = null
            fixture.paper.advanceTicks(1)
            assertEquals(0f, first.blockDamagePackets.last().progress)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `observing a maturing target reads fixed health without dirtying storage`() {
        Fixture().use { fixture ->
            val position = fixture.target.position
            fixture.paper.manager.putComponent(Maturing::class.java, position.world, position.x,
                position.y, position.z, Maturing(0L, 1L, 4.0))
            fixture.paper.manager.saveAll().join()
            val saves = fixture.paper.backend.saves
            fixture.observe(fixture.paper.installer)
            fixture.observe(fixture.paper.stranger)
            fixture.paper.advanceTicks(40)
            fixture.paper.manager.saveAll().join()

            assertEquals(saves, fixture.paper.backend.saves)
            val health = assertNotNull(fixture.target.getComponent(Health::class.java))
            assertEquals(50.0, health.health)
            assertEquals(100.0, health.maxHealth)
            assertTrue(fixture.paper.installer.blockDamagePackets.all { it.progress == 0.5f })
            assertTrue(fixture.paper.stranger.blockDamagePackets.all { it.progress == 0.5f })
            assertTrue(fixture.failures.isEmpty())
        }
    }

    private class Fixture : AutoCloseable {
        val paper = PaperTestFixture(initializeSystems = false, registerTypes = false, resolveMaterials = false)
        private val scheduler = PaperScheduler(paper.plugin)
        val failures = mutableListOf<Exception>()
        val observations = ObservationService<Player>(paper.manager, scheduler, failureHandler = failures::add)
        val system = CrackHealthObservationSystem(paper.plugin)
        val target: CitadelHandle

        init {
            paper.module.registerDefinition(CitadelDefinition(TYPE, listOf(initialHealth(50.0))), DamagePolicy.ALLOW)
            target = create(paper.position.x)
            observations.registerSystem(system)
            observations.enable()
        }

        fun create(x: Int, z: Int = paper.position.z, type: Key = TYPE): CitadelHandle {
            val position = CitadelPosition(paper.worldId, x, paper.position.y, z)
            paper.manager.loadChunk(position.world, x shr 4, z shr 4).join()
            assertEquals(ActionResult.SUCCESS, paper.module.actions.execute(PlaceAction(ActionContext.SYSTEM, position, type)))
            return assertNotNull(paper.manager.findCitadel(position.world, x, position.y, z))
        }

        fun observe(viewer: TestPlayer, targets: List<CitadelHandle> = listOf(target)): ObservationSubscription =
            observations.registerViewer(viewer.id, viewer.player, Observation { _, publisher ->
                publisher.publish(targets)
                ObservationSubscription {}
            })

        fun health(value: Double) {
            assertTrue(target.editComponent(Health::class.java) { it.health = value })
        }

        override fun close() {
            paper.players.values.forEach { it.blockDamageFailure = null }
            observations.disable()
            scheduler.close()
            paper.close()
        }
    }

    private class ReplacementHealth(maximum: Double, current: Double) : Health(maximum, current)

    private fun TestBlockDamage.position() = CitadelPosition(checkNotNull(location.world).uid,
        location.blockX, location.blockY, location.blockZ)

    private fun otherWorld(): World = paperProxy("other crack-display world") { method, _ ->
        when (method.name) {
            "getUID" -> UUID(80, 81)
            else -> unsupported(method)
        }
    }

    private companion object {
        val TYPE = Key("test", "crack_health")
        fun initialHealth(current: Double) = CitadelInitializer { _, draft ->
            draft.addComponent(Health::class.java, ReplacementHealth(100.0, current))
        }
    }
}
