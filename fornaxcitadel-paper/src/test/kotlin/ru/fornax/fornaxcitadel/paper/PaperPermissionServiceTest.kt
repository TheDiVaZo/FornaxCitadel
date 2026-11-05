package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Material
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionPosition
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionSource
import ru.fornax.fornaxcitadel.paper.modules.permission.PaperPermissionService
import ru.fornax.fornaxcitadel.paper.modules.permission.PaperPermissionValidator
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperPermissionServiceTest {
    @Test
    fun `destruction requests cannot use the environmental damage permission exemption`() {
        PaperTestFixture(initializeSystems = false).use { fixture ->
            val validator = PaperPermissionValidator(fixture.server, fixture.citadelTypes)
            val source = ExplosionSource.Block(ExplosionPosition(fixture.worldId, 1.0, 64.0, 2.0),
                4.0f, Material.TNT)
            val environmental = ActionContext(null, source)
            assertSame(ActionValidator.Result.Success, validator.validate(
                DamageAction(environmental, fixture.position, 1.0)
            ))
            assertSame(ActionValidator.Result.Rejected, validator.validate(
                DamageAction(environmental, fixture.position, Double.MAX_VALUE, DamageAction.Mode.DESTROY)
            ))
            fixture.stranger.gameMode = GameMode.CREATIVE
            fixture.stranger.permissions.remove("fornaxcitadel.damage")
            assertSame(ActionValidator.Result.Rejected, validator.validate(
                DamageAction(ActionContext(fixture.stranger.id, source), fixture.position,
                    Double.MAX_VALUE, DamageAction.Mode.DESTROY)
            ))
        }
    }

    @Test
    fun `permission lifecycle owns the supplied validator and leaves external checks registered`() {
        PaperTestFixture(initializeSystems = false).use { fixture ->
            val validator = PaperPermissionValidator(fixture.server, fixture.citadelTypes)
            val service = PaperPermissionService(fixture.module.actions, validator)
            val coordinator = fixture.module.actions.validatorCoordinator
            val action = PlaceAction(fixture.context(fixture.installer), fixture.position, PaperCitadelTypes.REINFORCED)
            var externalCalls = 0
            val external = object : ActionValidator<PlaceAction> {
                override val actionClass = PlaceAction::class.java
                override fun validate(action: PlaceAction): ActionValidator.Result {
                    externalCalls++
                    return ActionValidator.Result.Neutral
                }
            }
            coordinator.registerValidator(external)
            fixture.installer.permissions.clear()

            service.disable()
            service.enable()
            service.enable()
            assertSame(ActionValidator.Result.Rejected, coordinator.validate(action))
            service.disable()
            service.disable()
            assertSame(ActionValidator.Result.Neutral, coordinator.validate(action))
            assertEquals(2, externalCalls)

            service.enable()
            assertSame(ActionValidator.Result.Rejected, coordinator.validate(action))
            fixture.installer.permissions.add("fornaxcitadel.place")
            assertSame(ActionValidator.Result.Success, coordinator.validate(action))
            assertTrue(coordinator.unregisterValidator(validator))
            assertSame(ActionValidator.Result.Neutral, coordinator.validate(action))
            service.enable()
            assertSame(ActionValidator.Result.Success, coordinator.validate(action))
            service.disable()
            assertSame(ActionValidator.Result.Neutral, coordinator.validate(action))
            assertTrue(coordinator.unregisterValidator(external))
        }
    }
}
