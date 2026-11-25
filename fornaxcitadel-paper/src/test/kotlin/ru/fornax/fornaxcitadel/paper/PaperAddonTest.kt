package ru.fornax.fornaxcitadel.paper

import org.bukkit.entity.Player
import org.bukkit.event.Listener
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationService
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.paper.config.PaperMessages
import ru.fornax.fornaxcitadel.paper.config.PaperSettings
import ru.fornax.fornaxcitadel.paper.modules.breaking.PaperBreakingListener
import ru.fornax.fornaxcitadel.paper.modules.environment.PaperEnvironmentListener
import ru.fornax.fornaxcitadel.paper.modules.explosion.PaperExplosionListener
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypeSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.HealthSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperPlacementListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperAddonTest {
    @Test
    fun `configured definitions exist before chunk load and survive gameplay restarts`() = withAddon { paper, addon ->
        val module = paper.module
        assertFalse(module.citadels.isChunkLoaded(paper.worldId, 0, 0))
        val type = assertNotNull(addon.citadelTypes.find("test_wall"))
        val definition = assertNotNull(module.definitions.find(type))
        val health = module.coreAddon.health
        assertEquals(65.0, assertNotNull(health.findDefinition(type)).maxHealth)
        assertNotNull(health.maturation.findDefinition(type))
        assertTrue(paper.listenerRegistrations.isEmpty())

        paper.loadChunk(0, 0)
        repeat(2) { addon.onEnable() }
        module.coreAddon.onEnable()
        assertGameplayListeners(paper)
        paper.place(type)
        val citadel = assertNotNull(module.citadels.findProtecting(paper.position))
        assertEquals(65.0, health.currentHealth(citadel))

        repeat(2) { addon.onDisable() }
        assertTrue(paper.listenerRegistrations.isEmpty())
        addon.onEnable()
        assertGameplayListeners(paper)
        assertSame(definition, module.definitions.find(type))
        assertEquals(65.0, health.currentHealth(citadel))
    }

    @Test
    fun `conflicting repair processor survives rollback and gameplay can restart`() = withAddon { paper, addon ->
        val actions = paper.module.actions
        val repair = RepairAction(ActionContext.SYSTEM, paper.position)
        var executions = 0
        val external = object : ActionProcessor<RepairAction>() {
            override val actionClass = RepairAction::class.java
            override val actionKey = RepairAction.KEY
            override fun execute(action: RepairAction): ActionResult {
                executions++
                return ActionResult.SUCCESS
            }
        }
        actions.registerProcessor(external)
        assertFailsWith<IllegalArgumentException> { addon.onEnable() }
        assertTrue(paper.listenerRegistrations.isEmpty())
        assertEquals(ActionResult.SUCCESS, actions.execute(repair))
        assertEquals(1, executions)
        val dismantle = object : ActionProcessor<DismantleAction>() {
            override val actionClass = DismantleAction::class.java
            override val actionKey = DismantleAction.KEY
            override fun execute(action: DismantleAction) = ActionResult.SUCCESS
        }
        actions.registerProcessor(dismantle)
        addon.onDisable()
        assertTrue(actions.unregisterProcessor(dismantle))
        assertTrue(actions.unregisterProcessor(external))

        addon.onEnable()
        assertGameplayListeners(paper)
    }

    @Test
    fun `failed listener registration removes partial subscriptions and preserves a foreign listener`() = withAddon { paper, addon ->
        val external = object : Listener {}
        paper.server.pluginManager.registerEvents(external, paper.plugin)
        val failure = IllegalStateException("Bukkit listener registration failed")
        paper.listenerRegistrationFailure = failure

        assertSame(failure, assertFailsWith<IllegalStateException> { addon.onEnable() })
        assertEquals(listOf(external to paper.plugin), paper.listenerRegistrations)
        paper.listenerRegistrationFailure = null
        addon.onEnable()
        assertGameplayListeners(paper, external)
        addon.onDisable()
        assertEquals(listOf(external to paper.plugin), paper.listenerRegistrations)
    }

    private fun assertGameplayListeners(paper: PaperTestFixture, external: Listener? = null) {
        val expected = listOf(PaperPlacementListener::class, PaperBreakingListener::class,
            PaperExplosionListener::class, PaperEnvironmentListener::class)
        val active = paper.listenerRegistrations.filter { it.first !== external }
        assertEquals(expected.size, active.size)
        assertEquals(expected.toSet(), active.map { it.first::class }.toSet())
        assertTrue(active.all { it.second === paper.plugin })
    }

    private fun withAddon(test: (PaperTestFixture, PaperAddon) -> Unit) {
        PaperTestFixture(load = false, registerTypes = false, initializeSystems = false,
            initializeModule = false, trackListenerLifecycle = true).use { paper ->
            paper.actionValidationService.disable()
            paper.damageValidationService.disable()
            val module = paper.module
            val settings = PaperSettings(citadelTypes = CitadelTypesSettings(mapOf(
                "test_wall" to CitadelTypeSettings(Key("minecraft", "iron_ingot"), 1, health = HealthSettings(65.0))
            )))
            val addon = PaperAddon(paper.plugin, settings, PaperMessages.defaults(), module.actions, module.citadels,
                module.definitions, module.coreAddon.health, module.coreAddon.remove, paper.materials, paper.materialPlacement,
                ObservationService<Player>(paper.manager, ManualScheduler())) {}
            try {
                test(paper, addon)
            } finally {
                addon.onDisable()
            }
        }
    }
}
