package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Material
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.NamedActionSource
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionPosition
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionSource
import ru.fornax.fornaxcitadel.paper.modules.permission.PaperPermissionService
import ru.fornax.fornaxcitadel.paper.modules.permission.PaperPermissionValidator
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperPermissionServiceTest {
    @Test
    fun `non-player sources are ignored for every action even with actor attribution and an absent world`() {
        PaperTestFixture(initializeSystems = false).use { fixture ->
            val validator = PaperPermissionValidator(fixture.server, fixture.citadelTypes)
            val source = ExplosionSource.Block(ExplosionPosition(fixture.worldId, 1.0, 64.0, 2.0),
                4.0f, Material.TNT)
            val contexts = listOf(ActionContext.SYSTEM, ActionContext(fixture.installer.id, source),
                ActionContext(fixture.installer.id, NamedActionSource(Key("paper", "player"))))
            fixture.installer.permissions.add("fornaxcitadel.admin")
            fixture.worldAvailable = false
            fixture.worldChunkLoaded = false

            for (context in contexts) {
                assertFalse(validator.canAdminister(context))
                for (action in listOf(
                    PlaceAction(context, fixture.position, PaperCitadelTypes.INVULNERABLE),
                    RemoveAction(context, fixture.position),
                    DamageAction(context, fixture.position, 1.0),
                    DamageAction(context, fixture.position, 0.0, DamageAction.Mode.DESTROY)
                )) {
                    assertSame(ActionValidator.Result.Neutral, validator.validate(action))
                }
            }
        }
    }

    @Test
    fun `player permissions use the captured player instead of actor attribution or UUID lookup`() {
        PaperTestFixture(initializeSystems = false).use { fixture ->
            val validator = PaperPermissionValidator(fixture.server, fixture.citadelTypes)
            fixture.installer.permissions.add("fornaxcitadel.admin")
            val context = ActionContext(fixture.stranger.id, PlayerSource(fixture.installer.player))
            val checks: List<Triple<ActionData, String, ActionValidator.Result>> = listOf(
                Triple(PlaceAction(context, fixture.position, PaperCitadelTypes.REINFORCED),
                    "fornaxcitadel.place", ActionValidator.Result.Success),
                Triple(RemoveAction(context, fixture.position),
                    "fornaxcitadel.admin", ActionValidator.Result.Success),
                Triple(DamageAction(context, fixture.position, 1.0),
                    "fornaxcitadel.damage", ActionValidator.Result.Neutral),
                Triple(DamageAction(context, fixture.position, 0.0, DamageAction.Mode.DESTROY),
                    "fornaxcitadel.damage", ActionValidator.Result.Neutral)
            )
            fixture.players.clear()

            assertTrue(validator.canAdminister(context))
            for ((action, permission, accepted) in checks) {
                assertSame(accepted, validator.validate(action))
                fixture.installer.permissions.remove(permission)
                assertSame(ActionValidator.Result.Rejected, validator.validate(action))
                fixture.installer.permissions.add(permission)
            }

            fixture.installer.online = false
            assertFalse(validator.canAdminister(context))
            checks.forEach { (action, _, _) -> assertSame(ActionValidator.Result.Rejected, validator.validate(action)) }
            fixture.installer.online = true
            fixture.installer.gameMode = GameMode.SPECTATOR
            checks.forEach { (action, _, _) -> assertSame(ActionValidator.Result.Rejected, validator.validate(action)) }
            fixture.installer.gameMode = GameMode.SURVIVAL
            fixture.worldAvailable = false
            checks.forEach { (action, _, _) -> assertSame(ActionValidator.Result.Rejected, validator.validate(action)) }
            fixture.worldAvailable = true
            fixture.worldChunkLoaded = false
            checks.forEach { (action, _, _) -> assertSame(ActionValidator.Result.Rejected, validator.validate(action)) }
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
