package ru.fornax.fornaxcitadel.paper

import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.Event
import org.bukkit.event.block.Action as BukkitAction
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.config.PaperMessages
import ru.fornax.fornaxcitadel.paper.material.MaterialAdapter
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.dismantle.DismantleSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import kotlin.test.*

class OwnerlessCitadelTest {
    @Test
    fun `unowned targets reject executed queued and direct service calls even with external approval`() {
        for (kind in KINDS) for (entry in listOf("execute", "queued", "direct")) for (grant in listOf(false, true)) {
            var materialCalls = 0
            fixture { materials -> countLookups(materials) { materialCalls++ } }.use { f ->
                f.place()
                val original = f.citadel()
                original.editComponent(Health::class.java) { it.health = 5.0 }
                f.unown()
                f.installer.held = f.reinforcementStack(if (kind == "upgrade") HEAVY else PaperCitadelTypes.REINFORCED)
                val health = original.getComponent(Health::class.java)
                val type = original.getComponent(CitadelKind::class.java)
                val maturing = original.getComponent(Maturing::class.java)
                val startedAt = maturing?.startedAtMillis
                val item = f.installer.held.clone()
                val notifications = f.observe()
                val request = f.action(kind)
                if (grant) f.actions.registerValidator(object : ActionValidator<ru.fornax.fornaxcitadel.core.systems.action.Action> {
                    override val actionClass = Action::class.java
                    override fun validate(action: ru.fornax.fornaxcitadel.core.systems.action.Action) = ActionValidator.Result.Success
                })
                f.manager.saveAll().join()
                val saves = f.backend.saves
                assertFalse(f.actions.isAllowed(request), "$kind / $entry / $grant")
                when (entry) {
                    "execute" -> assertEquals(ActionResult.DENIED, f.actions.execute(request))
                    "queued" -> {
                        f.actions.post(request)
                        assertEquals(1, f.actions.drainPosted(1))
                        assertEquals(0, f.actions.drainPosted())
                    }
                    else -> assertEquals(ActionResult.DENIED, f.executeDirectly(request))
                }
                assertEquals(0, materialCalls, "$kind / $entry / $grant")
                assertTrue(original.isCurrent)
                assertNull(original.getComponent(CitadelOwner::class.java))
                assertSame(health, original.getComponent(Health::class.java))
                assertEquals(5.0, health?.health)
                assertSame(type, original.getComponent(CitadelKind::class.java))
                assertSame(maturing, original.getComponent(Maturing::class.java))
                assertEquals(startedAt, maturing?.startedAtMillis)
                assertEquals(item, f.installer.held)
                assertEquals(Material.STONE, f.block().type)
                assertEquals(0, f.vanillaBlockChanges)
                assertTrue(f.droppedItems.isEmpty())
                assertTrue(f.playerBreaks.isEmpty())
                assertTrue(notifications.isEmpty())
                f.manager.saveAll().join()
                assertEquals(saves, f.backend.saves)
            }
        }
    }

    @Test
    fun `right click never assigns an owner or consumes items regardless of health or requested operation`() {
        for (state in listOf("full", "implicit", "no maximum", "no health", "invulnerable", "damaged", "upgrade")) {
            fixture().use { f ->
                val type = if (state == "invulnerable") PaperCitadelTypes.INVULNERABLE else PaperCitadelTypes.REINFORCED
                f.installer.permissions.add("fornaxcitadel.admin")
                f.place(type)
                val original = f.citadel()
                f.unown()
                if (state in listOf("implicit", "no health")) f.removeHealth()
                if (state in listOf("no maximum", "no health")) f.coreGameplay.health.unregisterDefinition(type)
                if (state == "damaged") original.editComponent(Health::class.java) { it.health = 5.0 }
                val component = original.getComponent(Health::class.java)
                val health = component?.health
                val maturing = original.getComponent(Maturing::class.java)
                val startedAt = maturing?.startedAtMillis
                f.installer.held = f.reinforcementStack(if (state == "upgrade") HEAVY else type)
                val notifications = f.observe()
                val click = f.click()
                val itemUse = click.useItemInHand()
                val blockUse = click.useInteractedBlock()

                dispatch(f.placementListener, click, f.plugin)

                if (state == "damaged" || state == "upgrade") {
                    assertEquals(Event.Result.DENY, click.useItemInHand(), state)
                    val group = if (state == "upgrade") "upgrade" else "repair"
                    assertEquals<Any?>(PaperMessages.defaults().render("$group.denied"), f.installer.actionBarMessages.single())
                } else {
                    assertEquals(itemUse, click.useItemInHand(), state)
                    assertEquals(blockUse, click.useInteractedBlock(), state)
                    assertTrue(f.installer.messages.isEmpty(), state)
                }
                assertTrue(notifications.isEmpty(), state)
                assertNull(original.getComponent(CitadelOwner::class.java), state)
                assertEquals(type, original.getComponent(CitadelKind::class.java)?.definitionKey, state)
                assertSame(component, original.getComponent(Health::class.java), state)
                assertEquals(health, component?.health, state)
                assertSame(maturing, original.getComponent(Maturing::class.java), state)
                assertEquals(startedAt, maturing?.startedAtMillis, state)
                assertEquals(2, f.installer.held.amount, state)
                assertTrue(f.droppedItems.isEmpty(), state)
                assertEquals(0, f.vanillaBlockChanges, state)
            }
        }
    }

    private fun fixture(serviceMaterials: (MaterialRegistry) -> MaterialRegistry = { it }) =
        PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings(),
            dismantleSettings = DismantleSettings(1.0), serviceMaterials = serviceMaterials)

    private fun PaperTestFixture.citadel() = assertNotNull(citadels.findProtecting(position))
    private fun PaperTestFixture.unown() = checkNotNull(manager.findCitadel(worldId, 1, 64, 2)).removeComponent(CitadelOwner::class.java)
    private fun PaperTestFixture.removeHealth() = checkNotNull(manager.findCitadel(worldId, 1, 64, 2)).removeComponent(Health::class.java)
    private fun PaperTestFixture.click() = PlayerInteractEvent(installer.player, BukkitAction.RIGHT_CLICK_BLOCK,
        installer.held, block(), BlockFace.UP, EquipmentSlot.HAND)

    private fun PaperTestFixture.observe() = mutableListOf<ru.fornax.fornaxcitadel.core.systems.action.Action>().also { notifications ->
        actions.registerHandler(object : ActionHandler<ru.fornax.fornaxcitadel.core.systems.action.Action> {
            override val actionClass = Action::class.java
            override fun handle(action: ru.fornax.fornaxcitadel.core.systems.action.Action) { notifications.add(action) }
        })
    }

    private fun countLookups(materials: MaterialRegistry, callback: () -> Unit): MaterialRegistry =
        object : MaterialRegistry by materials {
            override fun getTypeOrNull(stack: ItemStack): MaterialAdapter? {
                callback()
                return materials.getTypeOrNull(stack)
            }
            override fun getType(key: Key): MaterialAdapter? {
                callback()
                return materials.getType(key)
            }
        }

    private fun PaperTestFixture.action(kind: String): ru.fornax.fornaxcitadel.core.systems.action.Action = when (kind) {
        "repair" -> RepairAction(context(installer), position)
        "upgrade" -> UpgradeAction(context(installer), position, HEAVY)
        else -> DismantleAction(context(installer), position)
    }

    private fun PaperTestFixture.executeDirectly(action: Action) = when (action) {
        is RepairAction -> repairService.repair(action)
        is UpgradeAction -> upgradeService.upgrade(action)
        is DismantleAction -> dismantleService.dismantle(action)
        else -> error("Unexpected action ${action.type}")
    }

    private companion object {
        val HEAVY = Key("fornaxcitadel", "reinforced_heavy")
        val KINDS = listOf("repair", "upgrade", "dismantle")
    }
}
