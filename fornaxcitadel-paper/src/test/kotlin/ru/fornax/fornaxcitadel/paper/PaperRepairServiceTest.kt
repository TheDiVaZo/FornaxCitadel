package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.material.MaterialAdapter
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.repair.PaperRepairService
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperRepairServiceTest {
    @Test
    fun `direct repair recognizes the held material once`() {
        PaperTestFixture(permissions = true).use { fixture ->
            fixture.place()
            fixture.damage()
            fixture.installer.held = fixture.reinforcementStack()
            var heldLookups = 0
            val materials = object : MaterialRegistry by fixture.materials {
                override fun getTypeOrNull(stack: ItemStack): MaterialAdapter? {
                    heldLookups++
                    return fixture.materials.getTypeOrNull(stack)
                }
            }
            fixture.repairService.disable()
            val service = PaperRepairService(fixture.module, fixture.citadelTypes, materials).also { it.enable() }
            try {
                val action = fixture.action()
                assertTrue(fixture.module.actions.isAllowed(action))
                assertEquals(0, heldLookups)

                assertEquals(ActionResult.SUCCESS, service.repair(action))

                assertEquals(1, heldLookups)
                assertEquals(100.0, fixture.health())
                assertEquals(1, fixture.installer.held.amount)
            } finally {
                service.disable()
            }
        }
    }

    @Test
    fun `owner repairs fully before completion handlers and only survival spends one item`() {
        for (mode in listOf(GameMode.SURVIVAL, GameMode.CREATIVE)) for (amount in listOf(1, 3)) {
            PaperTestFixture(permissions = true).use { fixture ->
                fixture.place()
                fixture.damage()
                fixture.installer.gameMode = mode
                fixture.installer.held = fixture.reinforcementStack(amount = amount)
                fixture.installer.permissions.remove("fornaxcitadel.place")
                val remaining = amount - if (mode == GameMode.CREATIVE) 0 else 1
                var completed = 0
                fixture.module.actions.handlerCoordinator.registerHandler(object : ActionHandler<RepairAction> {
                    override val actionClass = RepairAction::class.java
                    override fun handle(action: RepairAction) {
                        assertEquals(100.0, fixture.health())
                        assertEquals(remaining, fixture.installer.held.amount)
                        completed++
                    }
                })

                val event = fixture.interact()
                dispatch(fixture.placementListener, event, fixture.plugin)

                assertEquals(Event.Result.DENY, event.useInteractedBlock())
                assertEquals(Event.Result.DENY, event.useItemInHand())
                assertEquals(100.0, fixture.health())
                assertEquals(remaining, fixture.installer.held.amount)
                if (remaining == 0) assertEquals(Material.AIR, fixture.installer.held.type)
                assertEquals(1, completed)
                assertEquals<Any?>(PaperMessages.defaults().render("repair.success"), fixture.installer.actionBarMessages.single())
                assertEquals(Material.STONE, fixture.block().type)
                assertEquals(0, fixture.vanillaBlockChanges)
                assertTrue(fixture.playerBreaks.isEmpty())
                assertTrue(fixture.droppedItems.isEmpty())
            }
        }
    }

    @Test
    fun `full health unregistered material and missing health preserve ordinary interaction without feedback`() {
        for (reason in listOf("full", "wrong material", "missing health")) {
            PaperTestFixture(permissions = true).use { fixture ->
                fixture.place()
                if (reason != "full") fixture.damage()
                fixture.installer.held = fixture.reinforcementStack()
                when (reason) {
                    "wrong material" -> fixture.installer.held = TestItemStack(Material.DIRT, 2)
                    "missing health" -> assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2)).removeComponent(Health::class.java)
                }
                val event = fixture.interact()
                val blockUse = event.useInteractedBlock()
                val itemUse = event.useItemInHand()
                fixture.validate { error("No repair action should run for $reason") }

                dispatch(fixture.placementListener, event, fixture.plugin)

                assertEquals(blockUse, event.useInteractedBlock(), reason)
                assertEquals(itemUse, event.useItemInHand(), reason)
                assertEquals(2, fixture.installer.held.amount, reason)
                if (reason != "missing health") assertEquals(if (reason == "full") 100.0 else 10.0, fixture.health())
                assertTrue(fixture.installer.messages.isEmpty(), reason)
            }
        }
    }

    @Test
    fun `repair requires ownership and permission while external grants and vetoes work like dismantling`() {
        PaperTestFixture(permissions = true).use { fixture ->
            fixture.place()
            fixture.damage()
            fixture.installer.held = fixture.reinforcementStack()
            fixture.installer.permissions.remove("fornaxcitadel.repair")
            dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
            assertEquals(10.0, fixture.health())
            assertEquals(2, fixture.installer.held.amount)
            assertEquals<Any?>(PaperMessages.defaults().render("repair.denied"), fixture.installer.actionBarMessages.single())
            fixture.installer.permissions.add("fornaxcitadel.repair")

            fixture.stranger.held = fixture.reinforcementStack()
            fixture.stranger.permissions.add("fornaxcitadel.admin")
            val stranger = fixture.action(fixture.stranger)
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(stranger))
            val grant = fixture.validate { ActionValidator.Result.Success }
            val veto = fixture.validate { ActionValidator.Result.Rejected }
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(stranger))
            assertEquals(10.0, fixture.health())
            assertEquals(2, fixture.stranger.held.amount)
            fixture.module.actions.validatorCoordinator.unregisterValidator(veto)
            fixture.stranger.permissions.remove("fornaxcitadel.repair")
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(stranger))
            fixture.stranger.permissions.add("fornaxcitadel.repair")
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(stranger))
            assertEquals(100.0, fixture.health())
            assertEquals(1, fixture.stranger.held.amount)
            fixture.module.actions.validatorCoordinator.unregisterValidator(grant)

            fixture.damage()
            val owner = RepairAction(ActionContext(fixture.stranger.id, PlayerSource(fixture.installer.player)), fixture.position)
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(owner))
            assertEquals(1, fixture.installer.held.amount)
        }
    }

    @Test
    fun `offhand denied item use and stale item identity never repair or consume`() {
        for (reason in listOf("offhand", "denied", "stale")) {
            PaperTestFixture(permissions = true).use { fixture ->
                fixture.place()
                fixture.damage()
                fixture.installer.held = fixture.reinforcementStack()
                val original = fixture.installer.held
                val event = fixture.interact(hand = if (reason == "offhand") EquipmentSlot.OFF_HAND else EquipmentSlot.HAND)
                if (reason == "denied") event.setUseItemInHand(Event.Result.DENY)
                if (reason == "stale") fixture.installer.held = TestItemStack(Material.EMERALD, 2)
                val blockUse = event.useInteractedBlock()
                val itemUse = event.useItemInHand()

                dispatch(fixture.placementListener, event, fixture.plugin)

                assertEquals(10.0, fixture.health(), reason)
                assertEquals(2, original.amount, reason)
                assertEquals(2, fixture.installer.held.amount, reason)
                assertEquals(blockUse, event.useInteractedBlock(), reason)
                assertEquals(itemUse, event.useItemInHand(), reason)
                assertTrue(fixture.installer.messages.isEmpty(), reason)
            }
        }
    }

    @Test
    fun `queued repair checks current player target and item even with an external grant`() {
        for (reason in listOf("offline", "spectator", "world", "permission", "item", "full", "empty block", "chunk")) {
            PaperTestFixture(permissions = true).use { fixture ->
                fixture.place()
                fixture.damage()
                fixture.installer.held = fixture.reinforcementStack()
                fixture.validate { ActionValidator.Result.Success }
                assertEquals(ActionResult.DENIED, fixture.module.actions.execute(RepairAction(ActionContext.SYSTEM, fixture.position)))
                fixture.module.actions.post(fixture.action())
                when (reason) {
                    "offline" -> fixture.installer.online = false
                    "spectator" -> fixture.installer.gameMode = GameMode.SPECTATOR
                    "world" -> fixture.installer.location = Location(paperProxy<World>("other world") { method, _ ->
                        if (method.name == "getUID") UUID(99, 1) else unsupported(method)
                    }, 0.0, 64.0, 0.0)
                    "permission" -> fixture.installer.permissions.remove("fornaxcitadel.repair")
                    "item" -> fixture.installer.held = TestItemStack(Material.EMERALD, 2)
                    "full" -> fixture.damage(100.0)
                    "empty block" -> fixture.material(Material.AIR)
                    "chunk" -> fixture.worldChunkLoaded = false
                }
                var completed = 0
                fixture.module.actions.handlerCoordinator.registerHandler(object : ActionHandler<RepairAction> {
                    override val actionClass = RepairAction::class.java
                    override fun handle(action: RepairAction) { completed++ }
                })

                assertEquals(1, fixture.module.actions.drainPosted())

                assertEquals(0, completed, reason)
                assertEquals(if (reason == "full") 100.0 else 10.0, fixture.health(), reason)
                assertEquals(2, fixture.installer.held.amount, reason)
            }
        }
    }

    @Test
    fun `configured custom material repairs after reload and preserves maturation`() {
        PaperTestFixture(permissions = true, citadelSettings = typesWithItem(Key("custom", "original"), PaperCitadelTypes.MATURING)).use { fixture ->
            val original = TestMaterialAdapter(Key("custom", "original"))
            val different = TestMaterialAdapter(Key("custom", "different"))
            fixture.materials.register("custom", TestMaterialRegistry(original, different))
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(PlaceAction(
                fixture.context(fixture.installer), fixture.position, PaperCitadelTypes.MATURING)))
            val placed = assertNotNull(fixture.module.citadels.findProtecting(fixture.position))
            assertTrue(placed.editComponent(Maturing::class.java) { it.startedAtMillis = 1L })
            fixture.damage()
            fixture.manager.unloadChunk(fixture.worldId, 0, 0).join()
            fixture.loadChunk(0, 0)
            fixture.installer.held = different.createItemStack(2)
            dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
            assertEquals(10.0, fixture.health())
            assertEquals(2, fixture.installer.held.amount)
            fixture.installer.held = original.createItemStack(2)

            dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)

            assertEquals(40.0, fixture.health())
            assertEquals(1, fixture.installer.held.amount)
            assertTrue(original.isConsists(fixture.installer.held))
            fixture.manager.unloadChunk(fixture.worldId, 0, 0).join()
            fixture.loadChunk(0, 0)
            val restored = assertNotNull(fixture.module.citadels.findProtecting(fixture.position))
            assertEquals(40.0, restored.getComponent(Health::class.java)?.health)
            assertEquals(40.0, fixture.module.health.maxHealth(restored))
            assertEquals(1L, restored.getComponent(Maturing::class.java)?.startedAtMillis)
            assertEquals(fixture.installer.id, restored.getComponent(CitadelOwner::class.java)?.playerId)
            assertEquals(original.getKey(), fixture.citadelTypes.itemKey(restored))
        }
    }

    @Test
    fun `interactable repair requires sneaking and permits ordinary interaction at full health`() {
        for (kind in listOf("chest", "bed", "door")) {
            PaperTestFixture(permissions = true).use { fixture ->
                val (first, second) = when (kind) {
                    "chest" -> fixture.doubleChest()
                    "bed" -> fixture.bed()
                    else -> fixture.door()
                }
                val clicked = if (kind == "bed") first else second
                val other = if (kind == "bed") second else first
                assertEquals(ActionResult.SUCCESS, fixture.module.place.place(PlaceAction(
                    fixture.context(fixture.installer), clicked.testPosition(), PaperCitadelTypes.REINFORCED)))
                val target = assertNotNull(fixture.module.citadels.findProtecting(clicked.testPosition()))
                assertTrue(target.editComponent(Health::class.java) { it.health = 10.0 })
                fixture.installer.held = fixture.reinforcementStack()
                val ordinary = fixture.interact(block = clicked)
                val ordinaryBlockUse = ordinary.useInteractedBlock()
                val ordinaryItemUse = ordinary.useItemInHand()

                dispatch(fixture.placementListener, ordinary, fixture.plugin)

                assertEquals(10.0, target.getComponent(Health::class.java)?.health)
                assertEquals(2, fixture.installer.held.amount)
                assertEquals(ordinaryBlockUse, ordinary.useInteractedBlock())
                assertEquals(ordinaryItemUse, ordinary.useItemInHand())
                assertTrue(fixture.installer.messages.isEmpty())
                fixture.installer.sneaking = true
                val repair = fixture.interact(block = clicked)

                dispatch(fixture.placementListener, repair, fixture.plugin)

                assertEquals(Event.Result.DENY, repair.useInteractedBlock())
                assertEquals(Event.Result.DENY, repair.useItemInHand())
                assertTrue(target.isCurrent)
                assertEquals(100.0, target.getComponent(Health::class.java)?.health)
                assertEquals(1, fixture.installer.held.amount)
                fixture.installer.messages.clear()
                for (sneaking in listOf(false, true)) {
                    fixture.installer.sneaking = sneaking
                    val full = fixture.interact(block = other)
                    val blockUse = full.useInteractedBlock()
                    val itemUse = full.useItemInHand()
                    dispatch(fixture.placementListener, full, fixture.plugin)
                    assertEquals(blockUse, full.useInteractedBlock())
                    assertEquals(itemUse, full.useItemInHand())
                    assertEquals(1, fixture.installer.held.amount)
                    assertTrue(fixture.installer.messages.isEmpty())
                }
                assertEquals(0, fixture.vanillaBlockChanges)
            }
        }
    }

    @Test
    fun `repair lifecycle preserves external validators and restores owner approval`() {
        PaperTestFixture(permissions = true).use { fixture ->
            fixture.place()
            fixture.damage()
            fixture.installer.held = fixture.reinforcementStack()
            val action = fixture.action()
            assertSame(ActionValidator.Result.Success, fixture.module.actions.validatorCoordinator.validate(action))
            val grant = fixture.validate { ActionValidator.Result.Success }
            repeat(2) { fixture.repairService.disable() }
            assertSame(ActionValidator.Result.Success, fixture.module.actions.validatorCoordinator.validate(action))
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))
            repeat(2) { fixture.repairService.enable() }
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(action))
            assertEquals(1, fixture.installer.held.amount)
            assertTrue(fixture.module.actions.validatorCoordinator.unregisterValidator(grant))
        }
    }

    @Test
    fun `repair validation failure reports an error without changing health or items`() {
        PaperTestFixture(permissions = true).use { fixture ->
            fixture.place()
            fixture.damage()
            fixture.installer.held = fixture.reinforcementStack()
            fixture.validate { error("Repair validation failed") }
            val event = fixture.interact()

            dispatch(fixture.placementListener, event, fixture.plugin)

            assertEquals(Event.Result.DENY, event.useInteractedBlock())
            assertEquals(Event.Result.DENY, event.useItemInHand())
            assertEquals(10.0, fixture.health())
            assertEquals(2, fixture.installer.held.amount)
            assertEquals<Any?>(PaperMessages.defaults().render("repair.error"), fixture.installer.actionBarMessages.single())
        }
    }

    private fun PaperTestFixture.damage(health: Double = 10.0) {
        assertTrue(assertNotNull(module.citadels.findProtecting(position))
            .editComponent(Health::class.java) { it.health = health })
    }

    private fun PaperTestFixture.action(player: TestPlayer = installer, position: CitadelPosition = this.position) =
        RepairAction(context(player), position)

    private fun PaperTestFixture.interact(
        block: Block = block(), hand: EquipmentSlot = EquipmentSlot.HAND
    ) = PlayerInteractEvent(installer.player, Action.RIGHT_CLICK_BLOCK, installer.held, block, BlockFace.UP, hand)

    private fun PaperTestFixture.validate(check: (RepairAction) -> ActionValidator.Result) =
        object : ActionValidator<RepairAction> {
            override val actionClass = RepairAction::class.java
            override fun validate(action: RepairAction) = check(action)
        }.also(module.actions.validatorCoordinator::registerValidator)
}
