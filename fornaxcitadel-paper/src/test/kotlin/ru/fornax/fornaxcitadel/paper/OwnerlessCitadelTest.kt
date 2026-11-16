package ru.fornax.fornaxcitadel.paper

import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.claim.ClaimAction
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.material.MaterialAdapter
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.repair.PaperRepairService
import kotlin.test.*

class OwnerlessCitadelTest {
    @Test
    fun `right click claims full implicit and absent health without consuming or resetting maturation`() {
        for (state in listOf("full", "implicit", "no maximum", "no health", "invulnerable", "damaged")) {
            PaperTestFixture(permissions = true).use { f ->
                val type = if (state == "invulnerable") PaperCitadelTypes.INVULNERABLE else PaperCitadelTypes.REINFORCED
                f.installer.permissions.add("fornaxcitadel.admin")
                f.place(type)
                val original = f.citadel()
                f.unown()
                if (state in listOf("implicit", "no health")) f.removeHealth()
                if (state in listOf("no maximum", "no health")) f.module.health.unregisterDefinition(type)
                if (state == "damaged") original.editComponent(Health::class.java) { it.health = 10.0 }
                val component = original.getComponent(Health::class.java)
                val maturing = original.getComponent(Maturing::class.java)
                val startedAt = maturing?.startedAtMillis
                f.installer.held = f.reinforcementStack(type)
                val notifications = mutableListOf<Key>()
                f.module.actions.handlerCoordinator.registerHandler(object : ActionHandler<ActionData> {
                    override val actionClass = ActionData::class.java
                    override fun handle(action: ActionData) {
                        assertEquals(f.installer.id, original.getComponent(CitadelOwner::class.java)?.playerId)
                        assertEquals(if (state == "damaged") 1 else 2, f.installer.held.amount)
                        notifications.add(action.type)
                    }
                })

                val click = f.click()
                dispatch(f.placementListener, click, f.plugin)

                assertEquals(Event.Result.DENY, click.useItemInHand(), state)
                assertEquals(if (state == "damaged") listOf(ClaimAction.KEY, RepairAction.KEY) else listOf(ClaimAction.KEY), notifications, state)
                assertSame(component, original.getComponent(Health::class.java), state)
                assertSame(maturing, original.getComponent(Maturing::class.java), state)
                assertEquals(startedAt, maturing?.startedAtMillis, state)
                if (state == "damaged") assertEquals(100.0, component?.health)
                val message = if (state == "damaged") "repair.success" else "claim.success"
                assertEquals<Any?>(PaperMessages.defaults().render(message), f.installer.actionBarMessages.single())
                assertEquals(ActionResult.DENIED, f.module.actions.execute(f.repair()), state)
                f.manager.unloadChunk(f.worldId, 0, 0).join()
                f.loadChunk(0, 0)
                assertEquals(f.installer.id, f.citadel().getComponent(CitadelOwner::class.java)?.playerId, state)
                assertEquals(component?.health, f.citadel().getComponent(Health::class.java)?.health, state)
            }
        }
    }

    @Test
    fun `claim respects permissions vetoes current item and available definition`() {
        for (reason in listOf("permission", "veto", "item", "kind", "definition", "offline", "chunk")) {
            PaperTestFixture(permissions = true).use { f ->
                f.place()
                f.unown()
                val target = f.citadel()
                f.installer.held = f.reinforcementStack()
                when (reason) {
                    "permission" -> f.installer.permissions.remove("fornaxcitadel.repair")
                    "veto" -> f.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<ClaimAction> {
                        override val actionClass = ClaimAction::class.java
                        override fun validate(action: ClaimAction) = ActionValidator.Result.Rejected
                    })
                    "item" -> f.installer.held = TestItemStack(Material.DIRT, 2)
                    "kind" -> f.manager.removeComponent(CitadelKind::class.java, f.worldId, 1, 64, 2)
                    "definition" -> target.editComponent(CitadelKind::class.java) { it.definitionKey = Key("unknown", "type") }
                    "offline" -> f.installer.online = false
                    "chunk" -> f.worldChunkLoaded = false
                }
                dispatch(f.placementListener, f.click(), f.plugin)
                assertNull(target.getComponent(CitadelOwner::class.java), reason)
                assertEquals(100.0, target.getComponent(Health::class.java)?.health, reason)
                assertEquals(2, f.installer.held.amount, reason)
            }
        }
    }

    @Test
    fun `claim uses actual player and later players cannot take ownership even with external repair approval`() {
        PaperTestFixture(permissions = true).use { f ->
            f.place()
            f.unown()
            f.installer.held = f.reinforcementStack()
            val forgedActor = ClaimAction(ActionContext(f.stranger.id, PlayerSource(f.installer.player)), f.position)
            assertEquals(ActionResult.SUCCESS, f.module.actions.execute(forgedActor))
            assertEquals(f.installer.id, f.citadel().getComponent(CitadelOwner::class.java)?.playerId)
            f.stranger.held = f.reinforcementStack()
            f.citadel().editComponent(Health::class.java) { it.health = 10.0 }
            val stranger = RepairAction(f.context(f.stranger), f.position)
            assertEquals(ActionResult.DENIED, f.module.actions.execute(stranger))
            f.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<RepairAction> {
                override val actionClass = RepairAction::class.java
                override fun validate(action: RepairAction) = ActionValidator.Result.Success
            })
            assertEquals(ActionResult.SUCCESS, f.module.actions.execute(stranger))
            assertEquals(100.0, f.health())
            assertEquals(f.installer.id, f.citadel().getComponent(CitadelOwner::class.java)?.playerId)
        }
    }

    @Test
    fun `material callback changing owner health or item prevents repair and claim`() {
        for (change in listOf("owner", "health", "item", "removal")) PaperTestFixture(permissions = true).use { f ->
            f.place()
            f.unown()
            val original = f.citadel()
            original.editComponent(Health::class.java) { it.health = 10.0 }
            f.installer.held = f.reinforcementStack()
            f.repairService.disable()
            val materials = object : MaterialRegistry by f.materials {
                override fun getTypeOrNull(stack: ItemStack): MaterialAdapter? {
                    val result = f.materials.getTypeOrNull(stack)
                    when (change) {
                        "owner" -> f.manager.putComponent(CitadelOwner::class.java, f.worldId, 1, 64, 2, CitadelOwner(f.stranger.id))
                        "health" -> original.editComponent(Health::class.java) { it.health = 7.0 }
                        "item" -> f.installer.held = TestItemStack(Material.DIRT, 2)
                        "removal" -> f.manager.removeCitadel(original)
                    }
                    return result
                }
            }
            val service = PaperRepairService(f.module, f.citadelTypes, materials)
            service.enable()
            try {
                assertEquals(ActionResult.DENIED, f.module.actions.execute(f.repair()), change)
                assertEquals(if (change == "owner") f.stranger.id else null,
                    original.getComponent(CitadelOwner::class.java)?.playerId, change)
                assertEquals(2, f.installer.held.amount, change)
                if (original.isCurrent) assertEquals(if (change == "health") 7.0 else 10.0, f.health())
            } finally { service.disable() }
        }
    }

    @Test
    fun `upgrade claims unowned entity only on success before completion observers`() {
        val heavy = Key("fornaxcitadel", "reinforced_heavy")
        for (veto in listOf(false, true)) PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings()).use { f ->
            f.place()
            f.unown()
            val original = f.citadel()
            f.installer.held = f.reinforcementStack(heavy)
            var completed = 0
            f.module.actions.handlerCoordinator.registerHandler(object : ActionHandler<UpgradeAction> {
                override val actionClass = UpgradeAction::class.java
                override fun handle(action: UpgradeAction) {
                    assertEquals(f.installer.id, original.getComponent(CitadelOwner::class.java)?.playerId)
                    completed++
                }
            })
            if (veto) f.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<UpgradeAction> {
                override val actionClass = UpgradeAction::class.java
                override fun validate(action: UpgradeAction) = ActionValidator.Result.Rejected
            })
            assertEquals(if (veto) ActionResult.DENIED else ActionResult.SUCCESS,
                f.module.actions.execute(UpgradeAction(f.context(f.installer), f.position, heavy)))
            assertEquals(if (veto) 0 else 1, completed)
            assertEquals(if (veto) null else f.installer.id, original.getComponent(CitadelOwner::class.java)?.playerId)
            assertEquals(if (veto) 2 else 1, f.installer.held.amount)
            assertTrue(original.isCurrent)
        }
    }

    @Test
    fun `unowned dismantling respects permission and removes reinforcement without claiming or destroying block`() {
        for (allowed in listOf(false, true)) PaperTestFixture(permissions = true).use { f ->
            f.place()
            f.unown()
            if (!allowed) f.installer.permissions.remove("fornaxcitadel.dismantle")
            assertEquals(if (allowed) ActionResult.SUCCESS else ActionResult.DENIED,
                f.module.actions.execute(DismantleAction(f.context(f.installer), f.position)))
            assertEquals(allowed, f.module.citadels.findProtecting(f.position) == null)
            assertEquals(Material.STONE, f.block().type)
            assertEquals(if (allowed) 1 else 0, f.droppedItems.size)
            if (!allowed) assertNull(f.citadel().getComponent(CitadelOwner::class.java))
        }
    }

    private fun PaperTestFixture.citadel() = assertNotNull(module.citadels.findProtecting(position))
    private fun PaperTestFixture.unown() = manager.removeComponent(CitadelOwner::class.java, worldId, 1, 64, 2)
    private fun PaperTestFixture.removeHealth() = manager.removeComponent(Health::class.java, worldId, 1, 64, 2)
    private fun PaperTestFixture.repair() = RepairAction(context(installer), position)
    private fun PaperTestFixture.click() = PlayerInteractEvent(installer.player, Action.RIGHT_CLICK_BLOCK,
        installer.held, block(), BlockFace.UP, EquipmentSlot.HAND)
}
