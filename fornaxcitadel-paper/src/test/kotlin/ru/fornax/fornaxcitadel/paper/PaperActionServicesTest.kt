package ru.fornax.fornaxcitadel.paper

import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.action.OwnerActionValidator
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PaperActionServicesTest {
    @Test
    fun `disabled services retain their processor slots and prerequisite validators across restarts`() {
        for (kind in listOf("repair", "upgrade", "dismantle")) {
            PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings()).use { f ->
                f.place()
                val target = assertNotNull(f.citadels.findProtecting(f.position))
                val destination = Key("fornaxcitadel", "reinforced_heavy")
                f.installer.held = f.reinforcementStack(if (kind == "upgrade") destination else PaperCitadelTypes.REINFORCED)
                if (kind == "repair") target.editComponent(Health::class.java) { it.health = 5.0 }
                val service: LifecycleService = when (kind) {
                    "repair" -> f.repairService
                    "upgrade" -> f.upgradeService
                    else -> f.dismantleService
                }
                val action: Action = when (kind) {
                    "repair" -> RepairAction(f.context(f.installer), f.position)
                    "upgrade" -> UpgradeAction(f.context(f.installer), f.position, destination)
                    else -> DismantleAction(f.context(f.installer), f.position)
                }
                val missingTarget = f.position.copy(x = 3)
                val unavailableAction: Action = when (kind) {
                    "repair" -> RepairAction(f.context(f.installer), missingTarget)
                    "upgrade" -> UpgradeAction(f.context(f.installer), missingTarget, destination)
                    else -> DismantleAction(f.context(f.installer), missingTarget)
                }
                service.disable()
                val actions = f.actions
                val conflictingProcessor = object : ActionProcessor<Action>() {
                    override val actionClass = Action::class.java
                    override val actionKey = action.type
                    override fun execute(action: Action): ActionResult = error("Conflicting processor ran")
                }
                val grant = object : ActionValidator<Action> {
                    override val actionClass = Action::class.java
                    override fun validate(action: Action) = ActionValidator.Result.Success
                }
                actions.registerValidator(grant)
                assertFailsWith<IllegalArgumentException>(kind) { actions.registerProcessor(conflictingProcessor) }
                assertFalse(actions.isAllowed(unavailableAction), kind)
                assertEquals(ActionResult.DENIED, actions.execute(action), kind)
                assertEquals(2, f.installer.held.amount, kind)

                repeat(2) { service.enable() }
                assertFalse(actions.isAllowed(unavailableAction), kind)
                assertEquals(ActionResult.SUCCESS, actions.execute(action), kind)
                assertEquals(if (kind == "dismantle") 2 else 1, f.installer.held.amount, kind)
                repeat(2) { service.disable() }
                assertFalse(actions.isAllowed(unavailableAction), kind)
                assertTrue(actions.unregisterValidator(grant), kind)
            }
        }
    }

    @Test
    fun `owner validators preserve grants abstentions and their unavailable target policies`() {
        PaperTestFixture(permissions = true).use { f ->
            f.place()
            val repair = OwnerActionValidator(RepairAction::class.java, f.citadels)
            val upgrade = OwnerActionValidator(UpgradeAction::class.java, f.citadels, ActionValidator.Result.Rejected)
            val dismantle = OwnerActionValidator(DismantleAction::class.java, f.citadels)
            fun results(context: ActionContext) = listOf(
                repair.validate(RepairAction(context, f.position)),
                upgrade.validate(UpgradeAction(context, f.position, PaperCitadelTypes.REINFORCED)),
                dismantle.validate(DismantleAction(context, f.position))
            )
            val neutral = List(3) { ActionValidator.Result.Neutral }
            val success = List(3) { ActionValidator.Result.Success }
            val rejected = List(3) { ActionValidator.Result.Rejected }
            assertEquals<List<ActionValidator.Result>>(success, results(f.context(f.installer)))
            assertEquals<List<ActionValidator.Result>>(neutral, results(f.context(f.stranger)))
            assertEquals<List<ActionValidator.Result>>(neutral, results(ActionContext.SYSTEM))
            assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(CitadelOwner::class.java)
            assertEquals<List<ActionValidator.Result>>(rejected, results(f.context(f.installer)))
            assertEquals<List<ActionValidator.Result>>(rejected, results(f.context(f.stranger)))
            assertEquals<List<ActionValidator.Result>>(rejected, results(ActionContext.SYSTEM))
            f.manager.removeCitadel(assertNotNull(f.citadels.findProtecting(f.position)))
            assertEquals<List<ActionValidator.Result>>(neutral, results(f.context(f.installer)))
            f.manager.unloadChunk(f.worldId, 0, 0).join()
            assertEquals(listOf(ActionValidator.Result.Neutral, ActionValidator.Result.Rejected, ActionValidator.Result.Neutral),
                results(f.context(f.installer)))
            assertEquals(listOf(ActionValidator.Result.Neutral, ActionValidator.Result.Rejected, ActionValidator.Result.Neutral),
                results(ActionContext.SYSTEM))
        }
    }
}
