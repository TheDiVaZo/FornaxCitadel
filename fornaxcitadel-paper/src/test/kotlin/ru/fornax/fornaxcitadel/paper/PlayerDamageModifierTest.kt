package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.entity.Player
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifier
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifier.Priority
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifierService
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.modifiers.CreativeModeDamageModifier
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.modifiers.NerfingNewcomersModifier
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerDamageModifierTest {
    @Test
    fun `each calculation starts at one and passes the requested player and target exactly once`() {
        val fixture = PaperTestFixture()
        val target = fixture.placedTarget()
        val service = fixture.playerDamage
        assertEquals(1.0, service.getDamage(target, fixture.stranger.player))
        val initialValues = mutableListOf<Double>()
        val modifier = PlayerDamageModifier { player, citadel, currentDamage ->
            assertSame(fixture.stranger.player, player)
            assertSame(target, citadel)
            initialValues.add(currentDamage)
            currentDamage + 2.0
        }
        service.register(modifier)
        service.register(modifier)

        assertEquals(3.0, service.getDamage(target, fixture.stranger.player))
        assertEquals(3.0, service.getDamage(target, fixture.stranger.player))
        assertEquals(listOf(1.0, 1.0), initialValues)
    }

    @Test
    fun `higher priorities execute first and equal priorities retain registration order`() {
        val fixture = PaperTestFixture()
        val target = fixture.placedTarget()
        val visited = mutableListOf<String>()
        val inputs = mutableListOf<Double>()
        fun modifier(name: String, priority: Priority) = object : PlayerDamageModifier {
            override fun priority(): Priority = priority
            override fun getDamage(player: Player, citadel: CitadelHandle, currentDamage: Double): Double {
                visited.add(name)
                inputs.add(currentDamage)
                return currentDamage + 1.0
            }
        }
        fixture.playerDamage.register(modifier("normal first", Priority.NORMAL))
        fixture.playerDamage.register(modifier("lowest", Priority.LOWEST))
        fixture.playerDamage.register(modifier("highest", Priority.HIGHEST))
        fixture.playerDamage.register(modifier("low", Priority.LOW))
        fixture.playerDamage.register(modifier("normal second", Priority.NORMAL))
        fixture.playerDamage.register(modifier("high", Priority.HIGH))

        assertEquals(7.0, fixture.playerDamage.getDamage(target, fixture.installer.player))
        assertEquals(listOf("highest", "high", "normal first", "normal second", "low", "lowest"), visited)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), inputs)
    }

    @Test
    fun `invalid intermediate results stop the chain while zero remains valid damage`() {
        val fixture = PaperTestFixture()
        val target = fixture.placedTarget()
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1)) {
            val service = PlayerDamageModifierService()
            var laterModifierCalled = false
            service.register(PlayerDamageModifier { _, _, _ -> invalid })
            service.register(PlayerDamageModifier { _, _, _ -> laterModifierCalled = true; 1.0 })

            assertFailsWith<IllegalStateException> { service.getDamage(target, fixture.installer.player) }
            assertFalse(laterModifierCalled)
        }
        fixture.playerDamage.register(PlayerDamageModifier { _, _, _ -> 0.0 })
        assertEquals(0.0, fixture.playerDamage.getDamage(target, fixture.installer.player))
        assertEquals(100.0, fixture.health())
    }

    @Test
    fun `newcomer damage follows the faster age or play progress with clamping and rounding`() {
        val fixture = PaperTestFixture()
        val target = fixture.placedTarget()
        val threshold = Duration.ofDays(10)
        val modifier = NerfingNewcomersModifier(threshold, 1000L, CLOCK)
        // Age in days, played ticks, expected damage for a one-damage hit.
        val cases = listOf(
            Triple(0L, 0, 0.1),
            Triple(-1L, 0, 0.1),
            Triple(5L, 250, 0.5),
            Triple(2L, 750, 0.75),
            Triple(0L, 333, 0.33),
            Triple(10L, 0, 1.0),
            Triple(0L, 1000, 1.0),
            Triple(20L, 2000, 1.0)
        )
        for ((ageDays, playTicks, expected) in cases) {
            fixture.installer.firstPlayed = CLOCK.millis() - Duration.ofDays(ageDays).toMillis()
            fixture.installer.playTimeTicks = playTicks

            assertEquals(expected, modifier.getDamage(fixture.installer.player, target, 1.0),
                "age=$ageDays days, play=$playTicks ticks")
        }
    }

    @Test
    fun `creative damage remains finite and lethal after newcomer reduction regardless of registration order`() {
        val fixture = PaperTestFixture()
        val target = fixture.placedTarget()
        fixture.installer.firstPlayed = CLOCK.millis()
        val creative = CreativeModeDamageModifier()
        val newcomer = NerfingNewcomersModifier(Duration.ofDays(10), 1000L, CLOCK)
        for (modifiers in listOf(listOf(creative, newcomer), listOf(newcomer, creative))) {
            val service = PlayerDamageModifierService()
            modifiers.forEach(service::register)
            fixture.installer.gameMode = GameMode.CREATIVE
            val lethal = service.getDamage(target, fixture.installer.player)
            assertTrue(lethal.isFinite())
            assertEquals(Double.MAX_VALUE, lethal)

            fixture.installer.gameMode = GameMode.SURVIVAL
            assertEquals(0.1, service.getDamage(target, fixture.installer.player))
        }
    }

    @Test
    fun `newcomer rounding cannot turn finite large damage into infinity`() {
        val fixture = PaperTestFixture()
        val target = fixture.placedTarget()
        val modifier = NerfingNewcomersModifier(Duration.ofDays(10), 1000L, CLOCK)
        fixture.installer.firstPlayed = CLOCK.millis()

        val reduced = modifier.getDamage(fixture.installer.player, target, Double.MAX_VALUE)

        assertTrue(reduced.isFinite() && reduced > 0.0 && reduced < Double.MAX_VALUE)
        fixture.installer.playTimeTicks = 1000
        assertEquals(Double.MAX_VALUE, modifier.getDamage(fixture.installer.player, target, Double.MAX_VALUE))
    }

    private fun PaperTestFixture.placedTarget(): CitadelHandle {
        place()
        return assertNotNull(manager.findCitadel(worldId, position.x, position.y, position.z))
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC)
    }
}
