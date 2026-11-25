package ru.fornax.fornaxcitadel.paper

import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionMeta
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator.Result
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
            val actions = f.actionsWithPrerequisites()
            val invalid = listOf(f.position, f.position.copy(x = 16), f.position.copy(x = 524288),
                f.position.copy(y = -65))
            for (position in invalid) for (kind in KINDS) {
                assertFalse(actions.isAllowed(f.action(kind, position)), "$kind at $position")
            }
            assertFalse(f.manager.isChunkLoaded(f.worldId, 1, 0))
            f.place()
            val target = assertNotNull(f.citadels.findProtecting(f.position))
            assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(CitadelOwner::class.java)
            assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(Health::class.java)
            f.manager.saveAll().join()
            val saves = f.backend.saves
            val lookups = f.blockLookups
            val unrelated = Action(Key("test", "unrelated"), f.worldId, Int.MAX_VALUE, Int.MIN_VALUE, 0)
            for (action in KINDS.map { f.action(it) } + unrelated) {
                assertTrue(actions.isAllowed(action), action.type.toString())
                actions.registerActionMeta(action.type, ActionMeta(false))
                assertFalse(actions.isAllowed(action), action.type.toString())
            }
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
            val actions = f.actionsWithPrerequisites(approve = true)
            for (kind in KINDS) {
                val action = f.action(kind)
                actions.registerActionMeta(action.type, ActionMeta(false))
                assertTrue(actions.isAllowed(action), kind)
                assertFalse(actions.isAllowed(f.action(kind, f.position.copy(x = 3))), kind)
                assertFalse(actions.isAllowed(f.action(kind, f.position.copy(y = -65))), kind)
            }
            f.manager.unloadChunk(f.worldId, 0, 0).join()
            assertTrue(f.world.isChunkLoaded(0, 0))
            for (kind in KINDS) assertFalse(actions.isAllowed(f.action(kind)), kind)
            assertFalse(f.manager.isChunkLoaded(f.worldId, 0, 0))
        }
    }

    @Test
    fun `upgrade requires a registered destination but not a known source definition or kind`() {
        PaperTestFixture(resolveMaterials = false).use { f ->
            f.place()
            val target = assertNotNull(f.citadels.findProtecting(f.position))
            val validator = UpgradeActionValidator(f.citadels, f.coreAddon.definitions)
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
            val itemType = if (kind == "upgrade") PaperCitadelTypes.MATURING else PaperCitadelTypes.REINFORCED
            val action = if (kind == "upgrade") UpgradeAction(f.context(f.installer), f.position, itemType) else f.action(kind)
            f.installer.held = f.reinforcementStack(itemType)
            val completed = mutableListOf<Action>()
            f.actions.registerHandler(object : ActionHandler<Action> {
                override val actionClass = Action::class.java
                override fun handle(action: Action) {
                    completed.add(action)
                }
            })
            f.actions.registerValidator(approval())
            f.actions.post(action)
            f.manager.removeCitadel(assertNotNull(f.citadels.findProtecting(f.position)))
            assertEquals(1, f.actions.drainPosted(), kind)
            assertTrue(completed.isEmpty(), kind)

            f.actions.post(action)
            f.place()
            completed.clear()
            if (kind == "repair") {
                val target = assertNotNull(f.citadels.findProtecting(f.position))
                target.editComponent(Health::class.java) { it.health = 5.0 }
            }
            assertEquals(1, f.actions.drainPosted(), kind)
            assertEquals(listOf(action), completed, kind)
        }
    }

    private fun PaperTestFixture.actionsWithPrerequisites(approve: Boolean = false) = ActionManager().apply {
        if (approve) registerValidator(approval())
        registerValidator(ExistingCitadelValidator(RepairAction::class.java, citadels))
        registerValidator(UpgradeActionValidator(citadels, coreAddon.definitions))
        registerValidator(ExistingCitadelValidator(DismantleAction::class.java, citadels))
    }

    private fun PaperTestFixture.action(kind: String, target: CitadelPosition = position): Action = when (kind) {
        "repair" -> RepairAction(context(installer), target)
        "upgrade" -> UpgradeAction(context(installer), target, PaperCitadelTypes.REINFORCED)
        else -> DismantleAction(context(installer), target)
    }

    private fun approval() = object : ActionValidator<Action> {
        override val actionClass = Action::class.java
        override fun validate(action: Action) = Result.Success
    }

    private companion object {
        val KINDS = listOf("repair", "upgrade", "dismantle")
    }
}
