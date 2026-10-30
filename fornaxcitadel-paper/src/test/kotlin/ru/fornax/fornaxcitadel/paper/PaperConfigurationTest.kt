package ru.fornax.fornaxcitadel.paper

import org.bukkit.configuration.file.YamlConfiguration
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageSettings
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaperConfigurationTest {
    @Test
    fun `shipped config registers every configured definition with its intended health behavior`() {
        val settings = shippedSettings()
        val fixture = PaperTestFixture(load = false, registerTypes = false, citadelSettings = settings.citadelTypes)
        fixture.citadelTypes.register(fixture.module)
        fixture.manager.loadChunk(fixture.worldId, 0, 0).join()

        val handles = fixture.citadelTypes.names.mapIndexed { index, name ->
            val type = assertNotNull(fixture.citadelTypes.find(name))
            val position = CitadelPosition(fixture.worldId, index, 64, 2)
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(PlaceAction(fixture.context(fixture.installer), position, type)))
            name to assertNotNull(fixture.manager.findCitadel(position.world, position.x, position.y, position.z))
        }.toMap()

        for ((name, type) in settings.citadelTypes.types) {
            val handle = handles.getValue(name)
            when (val health = type.health) {
                is HealthBehaviorSettings.Simple -> {
                    assertEquals(health.max, handle.getComponent(Health::class.java)?.health)
                    assertNull(handle.getComponent(Maturing::class.java))
                }
                is HealthBehaviorSettings.Maturing -> {
                    assertEquals(health.initial, handle.getComponent(Health::class.java)?.health)
                    assertNotNull(handle.getComponent(Maturing::class.java))
                }
                null -> assertNull(handle.getComponent(Health::class.java))
            }
        }
    }

    @Test
    fun `invalid action and save intervals are rejected before startup`() {
        assertFailsWith<IllegalArgumentException> { PaperSettings(postedActionLimit = 0) }
        assertFailsWith<IllegalArgumentException> { PaperSettings(saveIntervalTicks = 0) }
    }

    @Test
    fun `player damage settings load toggles and convert newcomer hours to duration and ticks`() {
        assertEquals(PlayerDamageSettings(), shippedSettings().playerDamage)
        val config = YamlConfiguration().apply {
            set("player-damage.creative-mode", false)
            set("player-damage.newcomers.enabled", true)
            set("player-damage.newcomers.first-join-hours", 3)
            set("player-damage.newcomers.play-time-hours", 2)
        }

        val settings = PaperSettings.read(config).playerDamage

        assertFalse(settings.creativeModeEnabled)
        assertTrue(settings.newcomersEnabled)
        assertEquals(Duration.ofHours(3), settings.newcomerFirstJoin)
        assertEquals(144_000L, settings.newcomerPlayTimeTicks)
    }

    @Test
    fun `invalid newcomer thresholds and overflowing hour conversions are rejected before startup`() {
        for (key in listOf("first-join-hours", "play-time-hours")) {
            for (value in listOf(0L, -1L)) {
                val config = YamlConfiguration().apply { set("player-damage.newcomers.$key", value) }
                assertFailsWith<IllegalArgumentException> { PlayerDamageSettings.read(config) }
            }
            val overflow = YamlConfiguration().apply { set("player-damage.newcomers.$key", Long.MAX_VALUE) }
            assertFailsWith<ArithmeticException> { PlayerDamageSettings.read(overflow) }
        }
        assertFailsWith<IllegalArgumentException> { PlayerDamageSettings(newcomerFirstJoin = Duration.ofNanos(1)) }
    }

    private fun shippedSettings(): PaperSettings {
        val config = checkNotNull(javaClass.getResourceAsStream("/config.yml")).bufferedReader().use {
            YamlConfiguration.loadConfiguration(it)
        }
        return PaperSettings.read(config)
    }
}
