package ru.fornax.fornaxcitadel.paper

import org.bukkit.entity.Player
import org.bukkit.event.Listener
import ru.fornax.fornaxcitadel.addon.CoreGameplay
import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionMeta
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationService
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.config.PaperMessages
import ru.fornax.fornaxcitadel.paper.config.PaperSettings
import ru.fornax.fornaxcitadel.paper.modules.breaking.PaperBreakingListener
import ru.fornax.fornaxcitadel.paper.modules.environment.PaperEnvironmentListener
import ru.fornax.fornaxcitadel.paper.modules.explosion.PaperExplosionListener
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypeSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.modules.health.HealthDefinition
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperPlacementListener
import ru.fornax.fornaxcitadel.paper.modules.placement.register
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperGameplayTest {
    @Test
    fun `neutral validation requires approval for all five protected action keys`() = platform().use { paper ->
        val runtime = Runtime(paper)
        runtime.construct()

        for (key in listOf(RemoveAction.KEY, DamageAction.KEY, DismantleAction.KEY, RepairAction.KEY, UpgradeAction.KEY)) {
            assertFalse(runtime.actions.isAllowed(Action(key, paper.position)), key.toString())
        }
        assertTrue(runtime.actions.isAllowed(Action(PlaceAction.KEY, paper.position)))
        assertTrue(runtime.actions.isAllowed(Action(Key("addon", "example"), paper.position)))
    }

    @Test
    fun `paper defaults preserve explicitly configured action policies`() = platform().use { paper ->
        val runtime = Runtime(paper)
        runtime.actions.registerActionMeta(RepairAction.KEY, ActionMeta(true))
        runtime.construct()

        assertTrue(runtime.actions.isAllowed(Action(RepairAction.KEY, paper.position)))
    }

    @Test
    fun `configured definitions exist before gameplay construction and survive restarts`() = platform().use { paper ->
        val runtime = Runtime(paper)
        val type = Key("fornaxcitadel", "test_wall")
        val definition = assertNotNull(runtime.core.definitions.find(type))
        val health = runtime.core.healthFacade
        assertEquals(65.0, assertNotNull(health.findDefinition(type)).maxHealth)
        assertNotNull(runtime.core.maturationFacade.findDefinition(type))
        val addon = runtime.construct()
        assertFalse(paper.citadels.isChunkLoaded(paper.worldId, 0, 0))
        assertTrue(paper.listenerRegistrations.isEmpty())

        paper.loadChunk(0, 0)
        try {
            addon.onEnable()
            addon.onEnable()
            assertGameplayListeners(paper)
            val action = PlaceAction(paper.context(paper.installer), paper.position, type)
            assertEquals(ActionResult.SUCCESS, runtime.actions.execute(action))
            val citadel = assertNotNull(paper.citadels.findProtecting(paper.position))
            addon.onDisable()
            addon.onDisable()
            assertTrue(paper.listenerRegistrations.isEmpty())
            addon.onEnable()
            assertGameplayListeners(paper)
            assertSame(definition, runtime.core.definitions.find(type))
            assertEquals(65.0, health.currentHealth(citadel))
        } finally {
            addon.onDisable()
        }
    }

    @Test
    fun `conflicting processor survives failed construction before runtime services start`() = platform().use { paper ->
        val runtime = Runtime(paper)
        val external = object : ActionProcessor<RepairAction>() {
            override val actionClass = RepairAction::class.java
            override val actionKey = RepairAction.KEY
            override fun execute(action: RepairAction) = ActionResult.SUCCESS
        }
        runtime.actions.registerProcessor(external)
        assertFailsWith<IllegalArgumentException> { runtime.construct() }
        assertTrue(paper.listenerRegistrations.isEmpty())
        assertTrue(runtime.actions.unregisterProcessor(external))
    }

    @Test
    fun `failed environment startup releases earlier services and preserves a foreign listener`() = platform().use { paper ->
        val external = object : Listener {}
        paper.server.pluginManager.registerEvents(external, paper.plugin)
        val failure = IllegalStateException("Bukkit listener registration failed")
        paper.failingListenerType = PaperEnvironmentListener::class.java
        paper.listenerRegistrationFailure = failure
        val runtime = Runtime(paper)
        val addon = runtime.construct()

        assertSame(failure, assertFailsWith<IllegalStateException> { addon.onEnable() })
        assertEquals(listOf(external to paper.plugin), paper.listenerRegistrations)
        paper.listenerRegistrationFailure = null
        try {
            addon.onEnable()
            assertGameplayListeners(paper, external)
        } finally {
            addon.onDisable()
        }
        assertEquals(listOf(external to paper.plugin), paper.listenerRegistrations)
    }

    private fun assertGameplayListeners(paper: PaperTestFixture, external: Listener? = null) {
        val expected = listOf(PaperPlacementListener::class, PaperBreakingListener::class,
            PaperExplosionListener::class, PaperEnvironmentListener::class)
        val active = paper.listenerRegistrations.filter { it.first !== external }
        assertEquals(expected, active.map { it.first::class })
        assertTrue(active.all { it.second === paper.plugin })
    }

    private fun platform() = PaperTestFixture(load = false, registerTypes = false, initializeSystems = false,
        startQueue = false, trackListenerLifecycle = true)

    private class Runtime(private val paper: PaperTestFixture) {
        val actions = ActionManager()
        private val facade = ActionFacade(actions)
        val core = CoreGameplay(paper.citadels, facade)
        private val settings = PaperSettings(citadelTypes = CitadelTypesSettings(mapOf(
            "test_wall" to CitadelTypeSettings(Key("minecraft", "iron_ingot"), 1, health = HealthDefinition(65.0))
        )))

        init {
            settings.citadelTypes.register(core)
        }

        fun construct() =
            PaperGameplay(paper.plugin, settings, PaperMessages.defaults(), facade, paper.citadels,
                core.definitions, core.healthFacade, core.maturationFacade, paper.materials, paper.materialPlacement,
                ObservationService<Player>(paper.manager, ManualScheduler()))
    }
}
