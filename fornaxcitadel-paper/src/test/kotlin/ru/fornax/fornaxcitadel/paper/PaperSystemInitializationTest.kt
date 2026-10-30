package ru.fornax.fornaxcitadel.paper

import org.bukkit.event.Listener
import ru.fornax.fornaxcitadel.paper.modules.breaking.PaperBreakingListener
import ru.fornax.fornaxcitadel.paper.modules.environment.PaperEnvironmentListener
import ru.fornax.fornaxcitadel.paper.modules.explosion.PaperExplosionListener
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperPlacementListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperSystemInitializationTest {
    @Test
    fun `placement initializes without the other systems and cannot register twice`() {
        verifyInitialization(PaperPlacementListener::class.java) { placementService.init(plugin, module) }
    }

    @Test
    fun `breaking initializes without the other systems and cannot register twice`() {
        verifyInitialization(PaperBreakingListener::class.java) { breakingService.init(plugin, module) }
    }

    @Test
    fun `explosion protection initializes without the other systems and cannot register twice`() {
        verifyInitialization(PaperExplosionListener::class.java) { explosionService.init(plugin, module) }
    }

    @Test
    fun `environment protection initializes without the other systems and cannot register twice`() {
        verifyInitialization(PaperEnvironmentListener::class.java) { environmentService.init(plugin, module) }
    }

    private fun verifyInitialization(
        listenerClass: Class<out Listener>,
        initialize: PaperTestFixture.() -> Unit
    ) {
        val fixture = PaperTestFixture(initializeSystems = false)
        assertTrue(fixture.listenerRegistrations.isEmpty())

        fixture.initialize()

        val registration = fixture.listenerRegistrations.single()
        assertEquals(listenerClass, registration.first.javaClass)
        assertSame(fixture.plugin, registration.second)
        assertFailsWith<IllegalStateException> { fixture.initialize() }
        assertEquals(listOf(registration), fixture.listenerRegistrations)
    }
}
