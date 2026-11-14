package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Material
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.DamageModifier
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionPosition
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionSource
import ru.fornax.fornaxcitadel.paper.modules.health.PaperDamageValidator
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelDamagePolicy
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypeSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.HealthSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperDamageValidationTest {
    @Test
    fun `permissions and valid explosion sources do not authorize unknown types but an external validator can`() {
        PaperTestFixture(permissions = true).use { fixture ->
            val type = Key("external", "reinforcement")
            fixture.module.registerDefinition(CitadelDefinition(type, listOf(HealthInitializer(100.0))))
            fixture.place(type)
            val ordinary = DamageAction(fixture.context(fixture.stranger), fixture.position, 10.0)
            val source = ExplosionSource.Block(ExplosionPosition(fixture.worldId, 1.0, 64.0, 2.0),
                4.0f, Material.TNT)
            val explosion = DamageAction(ActionContext(null, source), fixture.position, 10.0)

            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(ordinary))
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(explosion))
            assertEquals(100.0, fixture.health())

            fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction): ActionValidator.Result {
                    val kind = fixture.module.citadels.findProtecting(action.position)
                        ?.getComponent(CitadelKind::class.java)
                    return if (action.mode == DamageAction.Mode.DAMAGE && kind?.definitionKey == type)
                        ActionValidator.Result.Success else ActionValidator.Result.Neutral
                }
            })

            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(ordinary))
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(explosion))
            assertEquals(80.0, fixture.health())
        }
    }

    @Test
    fun `configured damage denial vetoes ordinary damage but allows authorized creative destruction even with health`() {
        val settings = CitadelTypesSettings(mapOf("protected" to CitadelTypeSettings(
            Key("minecraft", "nether_star"), CitadelDamagePolicy.DENY, HealthSettings(100.0))))
        PaperTestFixture(permissions = true, citadelSettings = settings).use { fixture ->
            fixture.installer.permissions.add("fornaxcitadel.admin")
            fixture.place(Key("fornaxcitadel", "protected"))
            fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction) = ActionValidator.Result.Success
            })

            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
                DamageAction(fixture.context(fixture.stranger), fixture.position, 10.0)))
            assertEquals(100.0, fixture.health())

            val target = checkNotNull(fixture.module.citadels.findProtecting(fixture.position))
            fixture.module.health.registerModifier(DamageModifier { _, _, _ ->
                error("Creative destruction must not calculate damage")
            })
            fixture.stranger.gameMode = GameMode.CREATIVE
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(
                DamageAction(fixture.context(fixture.stranger), fixture.position, 0.0, DamageAction.Mode.DESTROY)))
            assertFalse(target.isCurrent)
        }
    }

    @Test
    fun `damage authorization lifecycle preserves external validators and is independent of breaking listeners`() {
        PaperTestFixture(permissions = true).use { fixture ->
            fixture.place()
            val action = DamageAction(fixture.context(fixture.stranger), fixture.position, 10.0)
            val coordinator = fixture.module.actions.validatorCoordinator
            val external = object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction) = ActionValidator.Result.Neutral
            }
            coordinator.registerValidator(external)
            fixture.breakingService.disable()
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(action))

            repeat(2) {
                fixture.damageValidationService.disable()
                fixture.damageValidationService.disable()
                assertSame(ActionValidator.Result.Neutral, coordinator.validate(action))
                assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))
                fixture.damageValidationService.enable()
                fixture.damageValidationService.enable()
                assertSame(ActionValidator.Result.Success, coordinator.validate(action))
            }

            assertTrue(coordinator.unregisterValidator(external))
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(action))
            assertEquals(80.0, fixture.health())
        }
    }

    @Test
    fun `damage validation rejects missing and unloaded targets without loading chunks`() {
        PaperTestFixture(load = false).use { fixture ->
            val validator = PaperDamageValidator(fixture.module.citadels, fixture.citadelTypes, fixture.server, true)
            val action = DamageAction(fixture.context(fixture.stranger), fixture.position, 10.0)
            assertSame(ActionValidator.Result.Rejected, validator.validate(action))
            assertFalse(fixture.manager.isChunkLoaded(fixture.worldId, 0, 0))

            fixture.loadChunk(0, 0)
            assertSame(ActionValidator.Result.Rejected, validator.validate(action))
            fixture.place()
            assertSame(ActionValidator.Result.Success, validator.validate(action))
            fixture.manager.unloadChunk(fixture.worldId, 0, 0).join()
            assertSame(ActionValidator.Result.Rejected, validator.validate(action))
            assertFalse(fixture.manager.isChunkLoaded(fixture.worldId, 0, 0))
        }
    }
}
