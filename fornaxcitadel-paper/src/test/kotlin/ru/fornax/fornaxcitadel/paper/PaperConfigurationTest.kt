package ru.fornax.fornaxcitadel.paper

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.fortification.Fortification
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.ownership.Owner
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PaperConfigurationTest {
    @Test
    fun `shipped config registers all four usable definitions with their intended components`() {
        val settings = shippedSettings()
        val fixture = PaperTestFixture(load = false, registerTypes = false)
        PaperCitadelTypes.register(fixture.module, settings)
        fixture.manager.loadChunk(fixture.worldId, 0, 0).join()

        val handles = PaperCitadelTypes.names.mapIndexed { index, name ->
            val type = assertNotNull(PaperCitadelTypes.find(name))
            val position = CitadelPosition(fixture.worldId, index, 64, 2)
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(PlaceAction(fixture.context(fixture.owner), position, type)))
            assertNotNull(fixture.manager.findCitadel(position.world, position.x, position.y, position.z))
        }

        assertEquals(1, handles[0].getComponent(Fortification::class.java)?.level)
        assertEquals(settings.fortifiedMaxHealth.first(), handles[0].getComponent(Health::class.java)?.health)
        assertEquals(fixture.owner.id, handles[0].getComponent(Owner::class.java)?.playerId)
        assertNotNull(handles[1].getComponent(Maturing::class.java))
        assertEquals(settings.initialMaxHealth, handles[1].getComponent(Health::class.java)?.health)
        assertEquals(settings.devMaxHealth, handles[2].getComponent(Health::class.java)?.health)
        assertNull(handles[2].getComponent(Fortification::class.java))
        assertNull(handles[3].getComponent(Health::class.java))
    }

    @Test
    fun `unsafe block types and invalid health configuration are rejected before startup`() {
        assertFailsWith<IllegalArgumentException> { PaperSettings(allowedBlocks = setOf(Material.SAND)) }
        assertFailsWith<IllegalArgumentException> { PaperSettings(fortifiedMaxHealth = listOf(40.0, 20.0)) }
        assertFailsWith<IllegalArgumentException> { PaperSettings(damagePerBreak = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { PaperSettings(postedActionLimit = 0) }
    }

    private fun shippedSettings(): PaperSettings {
        val config = checkNotNull(javaClass.getResourceAsStream("/config.yml")).bufferedReader().use {
            YamlConfiguration.loadConfiguration(it)
        }
        return PaperSettings.read(config)
    }
}
