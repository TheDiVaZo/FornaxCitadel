package ru.fornax.fornaxcitadel.paper

import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator.Result
import ru.fornax.fornaxcitadel.core.systems.action.ValidatorCoordinator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.core.systems.action.ExistingCitadelValidator
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.upgrade.UpgradeActionValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperActionPrerequisitesTest {
    @Test
    fun `typed prerequisites reject invalid targets without authorizing or changing valid targets`() {
        PaperTestFixture(resolveMaterials = false).use { f ->
            val validators = f.prerequisites()
            val invalid = listOf(f.position, f.position.copy(x = 16), f.position.copy(x = 524288),
                f.position.copy(y = -65))
            for (position in invalid) for (kind in KINDS) {
                assertSame(Result.Rejected, validators.validate(f.action(kind, position)), "$kind at $position")
            }
            assertFalse(f.manager.isChunkLoaded(f.worldId, 1, 0))
            f.place()
            val target = assertNotNull(f.module.citadels.findProtecting(f.position))
            assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(CitadelOwner::class.java)
            assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(Health::class.java)
            f.manager.saveAll().join()
            val saves = f.backend.saves
            val lookups = f.blockLookups
            for (kind in KINDS) {
                assertSame(Result.Neutral, validators.validate(f.action(kind)), kind)
            }
            assertSame(Result.Neutral, validators.validate(ActionData(Key("test", "unrelated"),
                f.worldId, Int.MAX_VALUE, Int.MIN_VALUE, 0)))
            assertTrue(target.isCurrent)
            assertNull(target.getComponent(CitadelOwner::class.java))
            assertNull(target.getComponent(Health::class.java))
            assertEquals(lookups, f.blockLookups)
            assertEquals(0, f.vanillaBlockChanges)
            assertTrue(f.droppedItems.isEmpty())
            f.manager.saveAll().join()
            assertEquals(saves, f.backend.saves)
        }
    }

    @Test
    fun `prerequisite rejection overrides earlier approval including unloaded stored data`() {
        PaperTestFixture(resolveMaterials = false).use { f ->
            f.place()
            val validators = f.prerequisites(approve = true)
            for (kind in KINDS) {
                assertSame(Result.Success, validators.validate(f.action(kind)), kind)
                assertSame(Result.Rejected, validators.validate(f.action(kind, f.position.copy(x = 3))), kind)
                assertSame(Result.Rejected, validators.validate(f.action(kind, f.position.copy(y = -65))), kind)
            }
            f.manager.unloadChunk(f.worldId, 0, 0).join()
            assertTrue(f.world.isChunkLoaded(0, 0))
            for (kind in KINDS) assertSame(Result.Rejected, validators.validate(f.action(kind)), kind)
            assertFalse(f.manager.isChunkLoaded(f.worldId, 0, 0))
        }
    }

    @Test
    fun `upgrade requires a registered destination but not a known source definition or kind`() {
        PaperTestFixture(resolveMaterials = false).use { f ->
            f.place()
            val target = assertNotNull(f.module.citadels.findProtecting(f.position))
            val validator = UpgradeActionValidator(f.module.citadels, f.module.definitions)
            target.editComponent(CitadelKind::class.java) { it.definitionKey = Key("test", "removed") }
            assertSame(Result.Rejected, validator.validate(UpgradeAction(ActionContext.SYSTEM, f.position, Key("test", "unknown"))))
            assertSame(Result.Neutral, validator.validate(UpgradeAction(ActionContext.SYSTEM, f.position, PaperCitadelTypes.REINFORCED)))
            assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(CitadelKind::class.java)
            assertSame(Result.Neutral, validator.validate(UpgradeAction(ActionContext.SYSTEM, f.position, PaperCitadelTypes.REINFORCED)))
            assertNull(target.getComponent(CitadelKind::class.java))
        }
    }

    @Test
    fun `service prerequisites evaluate queued target availability when drained`() {
        for (kind in KINDS) PaperTestFixture(resolveMaterials = false).use { f ->
            f.place()
            val action = f.action(kind)
            val executed = mutableListOf<ActionData>()
            val processors = f.module.actions.processorCoordinator
            processors.unregisterProcessor(assertNotNull(processors.getProcessor(action.type)))
            processors.registerProcessor(object : ActionProcessor<ActionData>() {
                override val actionClass = ActionData::class.java
                override val actionKey = action.type
                override fun execute(action: ActionData): ActionResult {
                    executed.add(action)
                    return ActionResult.SUCCESS
                }
            })
            f.module.actions.validatorCoordinator.registerValidator(approval())
            f.module.actions.post(action)
            f.manager.removeCitadel(assertNotNull(f.module.citadels.findProtecting(f.position)))
            assertEquals(1, f.module.actions.drainPosted(), kind)
            assertTrue(executed.isEmpty(), kind)

            f.module.actions.post(action)
            f.place()
            assertEquals(1, f.module.actions.drainPosted(), kind)
            assertEquals(listOf(action), executed, kind)
        }
    }

    private fun PaperTestFixture.prerequisites(approve: Boolean = false) = ValidatorCoordinator().apply {
        if (approve) registerValidator(approval())
        registerValidator(ExistingCitadelValidator(RepairAction::class.java, module.citadels))
        registerValidator(UpgradeActionValidator(module.citadels, module.definitions))
        registerValidator(ExistingCitadelValidator(DismantleAction::class.java, module.citadels))
    }

    private fun PaperTestFixture.action(kind: String, target: CitadelPosition = position): ActionData = when (kind) {
        "repair" -> RepairAction(context(installer), target)
        "upgrade" -> UpgradeAction(context(installer), target, PaperCitadelTypes.REINFORCED)
        else -> DismantleAction(context(installer), target)
    }

    private fun approval() = object : ActionValidator<ActionData> {
        override val actionClass = ActionData::class.java
        override fun validate(action: ActionData) = Result.Success
    }

    private companion object {
        val KINDS = listOf("repair", "upgrade", "dismantle")
    }
}
