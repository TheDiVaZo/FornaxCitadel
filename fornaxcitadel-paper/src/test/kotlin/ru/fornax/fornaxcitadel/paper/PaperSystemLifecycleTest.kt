package ru.fornax.fornaxcitadel.paper

import org.bukkit.Material
import org.bukkit.event.Listener
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.modules.breaking.PaperBreakingListener
import ru.fornax.fornaxcitadel.paper.modules.environment.PaperEnvironmentListener
import ru.fornax.fornaxcitadel.paper.modules.explosion.PaperExplosionListener
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionPowerSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperPlacementListener
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperSystemLifecycleTest {
    @Test
    fun `placement subscriptions can be disabled and enabled independently`() {
        verifyLifecycle(PaperPlacementListener::class.java) { placementService }
    }

    @Test
    fun `breaking subscriptions can be disabled and enabled independently`() {
        verifyLifecycle(PaperBreakingListener::class.java) { breakingService }
    }

    @Test
    fun `explosion subscriptions can be disabled and enabled independently`() {
        verifyLifecycle(PaperExplosionListener::class.java) { explosionService }
    }

    @Test
    fun `environment subscriptions can be disabled and enabled independently`() {
        verifyLifecycle(PaperEnvironmentListener::class.java) { environmentService }
    }

    @Test
    fun `placement removes only its validators and preserves reinforcement data and material bindings`() {
        PaperTestFixture(trackListenerLifecycle = true).use { fixture ->
            fixture.place()
            val handle = assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
            val kindType = assertNotNull(fixture.types.getType(CitadelKind::class.java))
            val healthType = assertNotNull(fixture.types.getType(Health::class.java))
            val health = assertNotNull(handle.getComponent(Health::class.java))
            val material = TestMaterialAdapter(Key("thirdparty", "reinforcement"))
            assertTrue(fixture.materials.register("thirdparty", TestMaterialRegistry(material)))
            fixture.placementService.registerMaterial(material.getKey(), PaperCitadelTypes.REINFORCED)
            fixture.installer.held = material.createItemStack(2)
            var externalCalls = 0
            val external = object : ActionValidator<PlaceAction> {
                override val actionClass = PlaceAction::class.java
                override fun validate(action: PlaceAction): ActionValidator.Result {
                    externalCalls++
                    return ActionValidator.Result.Neutral
                }
            }
            val validators = fixture.module.actions.validatorCoordinator
            validators.registerValidator(external)
            val action = PlaceAction(fixture.context(fixture.installer), fixture.position, PaperCitadelTypes.REINFORCED)
            fixture.material(Material.PISTON)
            assertEquals(ActionValidator.Result.Rejected, validators.validate(action))

            fixture.placementService.disable()

            assertEquals(ActionValidator.Result.Neutral, validators.validate(action))
            assertEquals(1, externalCalls)
            assertSame(kindType, fixture.types.getType(CitadelKind::class.java))
            assertSame(healthType, fixture.types.getType(Health::class.java))
            assertSame(health, handle.getComponent(Health::class.java))
            assertEquals(100.0, health.health)
            assertTrue(handle.isCurrent)
            assertEquals(material.getKey(), fixture.placementService.heldMaterialKey(fixture.installer.player, fixture.installer.held))
            val target = fixture.block(3, 64, 2)
            fixture.placementService.place(fixture.installer.player, target, material.getKey())
            assertNull(fixture.manager.findCitadel(fixture.worldId, 3, 64, 2))
            assertEquals(2, fixture.installer.held.amount)

            fixture.placementService.enable()

            assertEquals(ActionValidator.Result.Rejected, validators.validate(action))
            fixture.material(Material.STONE)
            assertEquals(ActionValidator.Result.Success, validators.validate(action))
            assertEquals(3, externalCalls)
            assertSame(health, handle.getComponent(Health::class.java))
            assertEquals(material.getKey(), fixture.placementService.heldMaterialKey(fixture.installer.player, fixture.installer.held))
            fixture.placementService.place(fixture.installer.player, target, material.getKey())
            assertNotNull(fixture.manager.findCitadel(fixture.worldId, 3, 64, 2))
            assertEquals(1, fixture.installer.held.amount)
        }
    }

    @Test
    fun `failed placement enable rolls back its partial subscriptions and permits another attempt`() {
        PaperTestFixture(initializeSystems = false, trackListenerLifecycle = true).use { fixture ->
            val failure = IllegalStateException("Listener registration failed after subscribing")
            fixture.listenerRegistrationFailure = failure
            assertSame(failure, assertFailsWith<IllegalStateException> { fixture.placementService.enable() })
            assertTrue(fixture.listenerRegistrations.isEmpty())
            fixture.material(Material.PISTON)
            val action = PlaceAction(fixture.context(fixture.installer), fixture.position, PaperCitadelTypes.REINFORCED)
            assertEquals(ActionValidator.Result.Neutral, fixture.module.actions.validatorCoordinator.validate(action))

            fixture.listenerRegistrationFailure = null
            fixture.placementService.enable()

            assertEquals(1, fixture.listenerRegistrations.size)
            assertEquals(ActionValidator.Result.Rejected, fixture.module.actions.validatorCoordinator.validate(action))
        }
    }

    @Test
    fun `explosion disable cancels only its tasks and old cleanup cannot affect the next lifecycle`() {
        PaperTestFixture(trackListenerLifecycle = true,
            explosionPowers = ExplosionPowerSettings(defaultEntityPower = 3.0f)).use { fixture ->
            val entity = UUID(10L, 20L)
            val anotherEntity = UUID(30L, 40L)
            var unrelatedRan = false
            val unrelatedTask = fixture.server.scheduler.runTask(fixture.plugin, Runnable { unrelatedRan = true })
            fixture.explosionService.recordPower(entity, 6.0f)
            fixture.explosionService.recordPower(anotherEntity, 7.0f)
            val cleanup = fixture.scheduledTasks[1]
            val anotherCleanup = fixture.scheduledTasks[2]

            fixture.explosionService.disable()
            fixture.explosionService.disable()

            assertTrue(cleanup.cancelled)
            assertTrue(anotherCleanup.cancelled)
            assertFalse(unrelatedTask.isCancelled)
            assertEquals(3.0f, fixture.explosionService.getEntityExplosionPower(entity))
            assertEquals(3.0f, fixture.explosionService.getEntityExplosionPower(anotherEntity))
            val taskCount = fixture.scheduledTasks.size
            fixture.explosionService.recordPower(entity, 8.0f)
            assertEquals(taskCount, fixture.scheduledTasks.size)
            assertEquals(3.0f, fixture.explosionService.getEntityExplosionPower(entity))

            fixture.explosionService.enable()
            fixture.explosionService.recordPower(entity, 9.0f)
            cleanup.action.run()
            assertEquals(9.0f, fixture.explosionService.getEntityExplosionPower(entity))
            fixture.runScheduledTasks()

            assertTrue(unrelatedRan)
            assertEquals(3.0f, fixture.explosionService.getEntityExplosionPower(entity))
        }
    }

    private fun verifyLifecycle(
        listenerClass: Class<out Listener>,
        select: PaperTestFixture.() -> LifecycleService
    ) {
        PaperTestFixture(initializeSystems = false, trackListenerLifecycle = true).use { fixture ->
            val unrelated = object : Listener {}
            fixture.server.pluginManager.registerEvents(unrelated, fixture.plugin)
            val service = fixture.select()
            service.disable()
            assertEquals(listOf(unrelated), fixture.listenerRegistrations.map { it.first })

            service.enable()
            service.enable()
            val registration = fixture.listenerRegistrations.single { it.first !== unrelated }
            assertEquals(listenerClass, registration.first.javaClass)
            assertSame(fixture.plugin, registration.second)
            assertEquals(2, fixture.listenerRegistrations.size)

            service.disable()
            service.disable()
            assertEquals(listOf(unrelated), fixture.listenerRegistrations.map { it.first })

            service.enable()
            assertEquals(2, fixture.listenerRegistrations.size)
            assertSame(registration.first, fixture.listenerRegistrations.single { it.first !== unrelated }.first)
        }
    }
}
