package ru.fornax.fornaxcitadel.paper

import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.modules.dismantle.OwnerDismantleValidator
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.repair.OwnerRepairValidator
import ru.fornax.fornaxcitadel.paper.modules.upgrade.OwnerUpgradeValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class PaperActionServicesTest {
    @Test
    fun `conflicting processor survives failed service enable and disable then service can retry`() {
        for (kind in listOf("repair", "upgrade", "dismantle")) {
            PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings()).use { f ->
                f.place()
                val target = assertNotNull(f.module.citadels.findProtecting(f.position))
                val destination = Key("fornaxcitadel", "reinforced_heavy")
                f.installer.held = f.reinforcementStack(if (kind == "upgrade") destination else PaperCitadelTypes.REINFORCED)
                if (kind == "repair") target.editComponent(Health::class.java) { it.health = 5.0 }
                val service: LifecycleService = when (kind) {
                    "repair" -> f.repairService
                    "upgrade" -> f.upgradeService
                    else -> f.dismantleService
                }
                val action: ActionData = when (kind) {
                    "repair" -> RepairAction(f.context(f.installer), f.position)
                    "upgrade" -> UpgradeAction(f.context(f.installer), f.position, destination)
                    else -> DismantleAction(f.context(f.installer), f.position)
                }
                val missingTarget = f.position.copy(x = 3)
                val unavailableAction: ActionData = when (kind) {
                    "repair" -> RepairAction(f.context(f.installer), missingTarget)
                    "upgrade" -> UpgradeAction(f.context(f.installer), missingTarget, destination)
                    else -> DismantleAction(f.context(f.installer), missingTarget)
                }
                service.disable()
                val processors = f.module.actions.processorCoordinator
                val validators = f.module.actions.validatorCoordinator
                var externalExecutions = 0
                val externalProcessor = object : ActionProcessor<ActionData>() {
                    override val actionClass = ActionData::class.java
                    override val actionKey = action.type
                    override fun execute(action: ActionData): ActionResult {
                        externalExecutions++
                        return ActionResult.SUCCESS
                    }
                }
                val externalValidator = object : ActionValidator<ActionData> {
                    override val actionClass = ActionData::class.java
                    override fun validate(action: ActionData) = ActionValidator.Result.Success
                }
                processors.registerProcessor(externalProcessor)
                validators.registerValidator(externalValidator)

                assertFailsWith<IllegalArgumentException>(kind) { service.enable() }
                assertSame(ActionValidator.Result.Success, validators.validate(unavailableAction), kind)
                repeat(2) { service.disable() }
                assertSame(externalProcessor, processors.getProcessor(action.type), kind)
                assertEquals(ActionResult.SUCCESS, f.module.actions.execute(action), kind)
                assertEquals(1, externalExecutions, kind)
                assertEquals(2, f.installer.held.amount, kind)
                validators.unregisterValidator(externalValidator)
                assertSame(ActionValidator.Result.Neutral, validators.validate(action), kind)

                processors.unregisterProcessor(externalProcessor)
                repeat(2) { service.enable() }
                assertSame(ActionValidator.Result.Rejected, validators.validate(unavailableAction), kind)
                assertEquals(ActionResult.SUCCESS, f.module.actions.execute(action), kind)
                assertEquals(1, externalExecutions, kind)
                assertEquals(if (kind == "repair" || kind == "upgrade") 1 else 2, f.installer.held.amount, kind)
                repeat(2) { service.disable() }
                assertNull(processors.getProcessor(action.type), kind)
                assertSame(ActionValidator.Result.Neutral, validators.validate(unavailableAction), kind)
            }
        }
    }

    @Test
    fun `owner validators preserve grants abstentions and their unavailable target policies`() {
        PaperTestFixture(permissions = true).use { f ->
            f.place()
            val repair = OwnerRepairValidator(f.module.citadels)
            val upgrade = OwnerUpgradeValidator(f.module.citadels)
            val dismantle = OwnerDismantleValidator(f.module.citadels)
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
            f.manager.removeComponent(CitadelOwner::class.java, f.worldId, 1, 64, 2)
            assertEquals<List<ActionValidator.Result>>(rejected, results(f.context(f.installer)))
            assertEquals<List<ActionValidator.Result>>(rejected, results(f.context(f.stranger)))
            assertEquals<List<ActionValidator.Result>>(rejected, results(ActionContext.SYSTEM))
            f.manager.removeCitadel(assertNotNull(f.module.citadels.findProtecting(f.position)))
            assertEquals<List<ActionValidator.Result>>(neutral, results(f.context(f.installer)))
            f.manager.unloadChunk(f.worldId, 0, 0).join()
            assertEquals(listOf(ActionValidator.Result.Neutral, ActionValidator.Result.Rejected, ActionValidator.Result.Neutral),
                results(f.context(f.installer)))
            assertEquals(listOf(ActionValidator.Result.Neutral, ActionValidator.Result.Rejected, ActionValidator.Result.Neutral),
                results(ActionContext.SYSTEM))
        }
    }
}
