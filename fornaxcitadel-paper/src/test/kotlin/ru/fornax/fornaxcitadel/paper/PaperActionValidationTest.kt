package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.validation.PaperActionStateValidator
import ru.fornax.fornaxcitadel.core.systems.action.ValidatorRegistration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperActionValidationTest {
    @Test
    fun `invalid platform or player state stops processors despite an earlier explicit grant`() {
        PaperTestFixture(initializeSystems = false).use { fixture ->
            val probe = Probe(fixture)
            val context = fixture.context(fixture.installer)
            fun actions(position: CitadelPosition = fixture.position) = listOf(
                PlaceAction(context, position, PaperCitadelTypes.REINFORCED),
                DamageAction(context, position, 1.0),
                RemoveAction(context, position), DismantleAction(context, position)
            )
            fun assertDenied(position: CitadelPosition = fixture.position) {
                actions(position).forEach { assertEquals(ActionResult.DENIED, probe.actions.execute(it)) }
            }

            actions().forEach { assertSame(ActionValidator.Result.Neutral, probe.validator.validate(it)) }
            fixture.worldAvailable = false
            assertDenied()
            fixture.worldAvailable = true
            fixture.worldChunkLoaded = false
            assertDenied()
            fixture.worldChunkLoaded = true
            for (position in listOf(fixture.position.copy(y = -65), fixture.position.copy(y = 320),
                fixture.position.copy(x = Int.MAX_VALUE), fixture.position.copy(z = Int.MIN_VALUE))) {
                assertDenied(position)
            }
            fixture.installer.online = false
            assertDenied()
            fixture.installer.online = true
            fixture.installer.gameMode = GameMode.SPECTATOR
            assertDenied()
            assertEquals(0, probe.processorCalls)

            fixture.installer.gameMode = GameMode.SURVIVAL
            actions().forEach { assertEquals(ActionResult.SUCCESS, probe.actions.execute(it)) }
            assertEquals(4, probe.processorCalls)
        }
    }

    @Test
    fun `dismantle requires a player in the target world and a nonempty block before processing`() {
        PaperTestFixture(initializeSystems = false).use { fixture ->
            val probe = Probe(fixture)
            val action = DismantleAction(fixture.context(fixture.installer), fixture.position)
            assertEquals(ActionResult.DENIED, probe.actions.execute(DismantleAction(ActionContext.SYSTEM, fixture.position)))
            fixture.installer.location = Location(otherWorld(), 1.0, 64.0, 2.0)
            assertEquals(ActionResult.DENIED, probe.actions.execute(action))
            fixture.installer.location = fixture.block().location
            for (air in listOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR)) {
                fixture.material(air)
                assertEquals(ActionResult.DENIED, probe.actions.execute(action))
            }
            assertEquals(0, probe.processorCalls)

            // Other actions can be environmental and need not break a block physically.
            assertEquals(ActionResult.SUCCESS, probe.actions.execute(DamageAction(ActionContext.SYSTEM, fixture.position, 1.0)))
            fixture.worldAvailable = false
            assertEquals(ActionResult.DENIED, probe.actions.execute(DamageAction(ActionContext.SYSTEM, fixture.position, 1.0)))
            assertEquals(1, probe.processorCalls)
        }
    }

    @Test
    fun `state validation grants no ownership and lifecycle removes only its own registration`() {
        PaperTestFixture().use { fixture ->
            fixture.place()
            val stranger = DismantleAction(fixture.context(fixture.stranger), fixture.position)
            assertSame(ActionValidator.Result.Neutral, PaperActionStateValidator(fixture.server).validate(stranger))
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(stranger))
            assertTrue(fixture.playerBreaks.isEmpty())

            val probe = Probe(fixture)
            val action = DamageAction(ActionContext.SYSTEM, fixture.position, 1.0)
            fixture.worldAvailable = false
            repeat(2) {
                assertEquals(ActionResult.DENIED, probe.actions.execute(action))
                probe.service.disable()
                probe.service.disable()
                assertEquals(ActionResult.SUCCESS, probe.actions.execute(action))
                probe.service.enable()
                probe.service.enable()
            }
            assertEquals(ActionResult.DENIED, probe.actions.execute(action))
            assertEquals(2, probe.processorCalls)
            val custom = ActionData(Key("test", "unrelated"), fixture.position, ActionContext.SYSTEM)
            assertSame(ActionValidator.Result.Neutral, probe.validator.validate(custom))
        }
    }

    @Test
    fun `queued dismantle checks current player state even without permission validation`() {
        PaperTestFixture(permissions = false).use { fixture ->
            fixture.place()
            val action = DismantleAction(fixture.context(fixture.installer), fixture.position)
            val changes: List<(TestPlayer) -> Unit> = listOf(
                { it.online = false },
                { it.gameMode = GameMode.SPECTATOR },
                { it.location = Location(otherWorld(), 1.0, 64.0, 2.0) }
            )
            for (change in changes) {
                fixture.module.actions.post(action)
                change(fixture.installer)
                assertEquals(1, fixture.module.actions.drainPosted())
                assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
                assertTrue(fixture.playerBreaks.isEmpty())
                fixture.installer.online = true
                fixture.installer.gameMode = GameMode.SURVIVAL
                fixture.installer.location = fixture.block().location
            }
            fixture.module.actions.post(action)
            assertEquals(1, fixture.module.actions.drainPosted())
            assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
            assertTrue(fixture.playerBreaks.isEmpty())
            assertEquals(Material.STONE, fixture.block().type)
        }
    }

    private class Probe(fixture: PaperTestFixture) {
        val actions = ActionManager(fixture.manager)
        val validator = PaperActionStateValidator(fixture.server)
        val service = ValidatorRegistration(actions, validator)
        var processorCalls = 0
            private set

        init {
            actions.validatorCoordinator.registerValidator(object : ActionValidator<ActionData> {
                override val actionClass = ActionData::class.java
                override fun validate(action: ActionData) = ActionValidator.Result.Success
            })
            for (key in listOf(PlaceAction.KEY, DamageAction.KEY, RemoveAction.KEY, DismantleAction.KEY)) {
                actions.processorCoordinator.registerProcessor(object : ActionProcessor<ActionData>() {
                    override val actionClass = ActionData::class.java
                    override val actionKey = key
                    override fun execute(action: ActionData): ActionResult {
                        processorCalls++
                        return ActionResult.SUCCESS
                    }
                })
            }
            service.enable()
        }
    }

    private fun otherWorld(): World = paperProxy("other action world") { method, _ ->
        if (method.name == "getUID") UUID(99, 1) else unsupported(method)
    }
}
