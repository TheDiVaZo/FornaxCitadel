package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.ExplosionResult
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Chest
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.paper.modules.placement.BlockListType
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.placement.PlacementSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.ReinforceableBlockValidator
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChestReinforcementTest {
    @Test
    fun `all chest materials keep the selected half responsible in four directions`() {
        fixture().use { fixture ->
            for (material in listOf(Material.CHEST, Material.TRAPPED_CHEST) + copperChestMaterials) {
                for (facing in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
                    val (left, right) = fixture.doubleChest(facing = facing, leftMaterial = material)
                    for (part in listOf(left, right)) {
                        assertEquals(part.testPosition(),
                            fixture.materialPlacement.getResponsiblePosition(part.testPosition()), "$material $facing")
                    }
                    val rule = fixture.materialPlacement.getRule(left)
                    assertEquals(right.testPosition(), rule.getRelatedBlock(left)?.testPosition())
                    assertEquals(left.testPosition(), rule.getRelatedBlock(right)?.testPosition())
                }
            }
        }
    }

    @Test
    fun `copper halves remain linked across every oxidation and wax combination`() {
        fixture().use { fixture ->
            for (leftMaterial in copperChestMaterials) {
                for (rightMaterial in copperChestMaterials) {
                    val (left, right) = fixture.doubleChest(leftMaterial = leftMaterial, rightMaterial = rightMaterial)
                    assertEquals(right.testPosition(),
                        fixture.materialPlacement.getRule(left).getRelatedBlock(left)?.testPosition())
                    assertEquals(left.testPosition(),
                        fixture.materialPlacement.getRule(right).getRelatedBlock(right)?.testPosition())
                }
            }
        }
    }

    @Test
    fun `single missing mismatched and unrelated chest halves stay independent`() {
        fixture().use { fixture ->
            val (left, right) = fixture.doubleChest()
            fixture.placeExact(left)
            val invalidPartners = listOf(
                Triple(Material.AIR, Chest.Type.LEFT, BlockFace.NORTH),
                Triple(Material.TRAPPED_CHEST, Chest.Type.LEFT, BlockFace.NORTH),
                Triple(Material.COPPER_CHEST, Chest.Type.LEFT, BlockFace.NORTH),
                Triple(Material.CHEST, Chest.Type.SINGLE, BlockFace.NORTH),
                Triple(Material.CHEST, Chest.Type.RIGHT, BlockFace.NORTH),
                Triple(Material.CHEST, Chest.Type.LEFT, BlockFace.SOUTH)
            )
            for ((material, type, facing) in invalidPartners) {
                fixture.material(material, left.testPosition())
                fixture.blockData(chestData(type, facing, material), left.testPosition())
                assertEquals(right.testPosition(),
                    fixture.materialPlacement.getResponsiblePosition(right.testPosition()))
                assertNull(fixture.module.citadels.findProtecting(right.testPosition()))
            }
            fixture.doubleChest()
            fixture.blockData(chestData(Chest.Type.SINGLE, BlockFace.NORTH), right.testPosition())
            assertEquals(right.testPosition(), fixture.materialPlacement.getResponsiblePosition(right.testPosition()))
            assertNull(fixture.module.citadels.findProtecting(right.testPosition()))
        }
    }

    @Test
    fun `item placement stores the selected half and counterpart duplicates consume no item`() {
        for (material in listOf(Material.CHEST, Material.TRAPPED_CHEST) + copperChestMaterials) {
            for (selectLeft in listOf(false, true)) {
                fixture().use { fixture ->
                    val (left, right) = fixture.doubleChest(leftMaterial = material)
                    val selected = if (selectLeft) left else right
                    val other = if (selectLeft) right else left
                    fixture.installer.held = fixture.reinforcementStack(amount = 3)
                    for (part in listOf(selected, other, selected)) {
                        val event = PlayerInteractEvent(fixture.installer.player, Action.RIGHT_CLICK_BLOCK,
                            fixture.installer.held, part, BlockFace.UP, EquipmentSlot.HAND)
                        dispatch(fixture.placementListener, event, fixture.plugin)
                        assertEquals(Event.Result.DENY, event.useInteractedBlock())
                    }
                    assertEquals(2, fixture.installer.held.amount)
                    assertNotNull(fixture.materialPlacement.findExact(selected))
                    assertNull(fixture.materialPlacement.findExact(other))
                }
            }
        }
    }

    @Test
    fun `legacy reinforcement on either half prevents duplicates and retains local priority`() {
        for (legacyLeft in listOf(false, true)) {
            fixture().use { fixture ->
                val (left, right) = fixture.doubleChest()
                val legacy = fixture.placeExact(if (legacyLeft) left else right)
                for (part in listOf(left, right)) {
                    assertEquals(legacy.runtimeId,
                        assertNotNull(fixture.module.citadels.findProtecting(part.testPosition())).runtimeId)
                    assertEquals(ActionResult.DENIED, fixture.module.actions.execute(PlaceAction(
                        fixture.context(fixture.installer), part.testPosition(), PaperCitadelTypes.REINFORCED)))
                }
                val other = fixture.placeExact(if (legacyLeft) right else left)
                assertEquals(legacy.runtimeId,
                    assertNotNull(fixture.module.citadels.findProtecting(legacy.position)).runtimeId)
                assertEquals(other.runtimeId,
                    assertNotNull(fixture.module.citadels.findProtecting(other.position)).runtimeId)
            }
        }
    }

    @Test
    fun `splitting a chest keeps reinforcement on its original position without copying`() {
        for (legacyLeft in listOf(false, true)) {
            fixture().use { fixture ->
                val (left, right) = fixture.doubleChest()
                val reinforced = if (legacyLeft) left else right
                val unreinforced = if (legacyLeft) right else left
                val handle = fixture.placeExact(reinforced)
                for (part in listOf(left, right)) {
                    fixture.blockData(chestData(Chest.Type.SINGLE, BlockFace.NORTH), part.testPosition())
                }
                assertEquals(handle.runtimeId,
                    assertNotNull(fixture.module.citadels.findProtecting(reinforced.testPosition())).runtimeId)
                assertNull(fixture.module.citadels.findProtecting(unreinforced.testPosition()))
                assertNull(fixture.materialPlacement.findExact(unreinforced))
                assertTrue(handle.isCurrent)
                assertEquals(reinforced.testPosition(), handle.position)
            }
        }
    }

    @Test
    fun `raw and queued damage and removal reach the opposite half without changing action coordinates`() {
        for (selectLeft in listOf(false, true)) {
            fixture().use { fixture ->
                val (left, right) = fixture.doubleChest()
                val selected = (if (selectLeft) left else right).testPosition()
                val source = (if (selectLeft) right else left).testPosition()
                val observed = mutableListOf<CitadelPosition>()
                fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
                    override val actionClass = DamageAction::class.java
                    override fun validate(action: DamageAction): ActionValidator.Result {
                        observed.add(action.position)
                        return ActionValidator.Result.Neutral
                    }
                })
                assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(PlaceAction(
                    fixture.context(fixture.installer), selected, PaperCitadelTypes.REINFORCED)))
                val handle = assertNotNull(fixture.module.citadels.findProtecting(source))
                assertEquals(selected, handle.position)
                assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(DamageAction(
                    fixture.context(fixture.stranger), source, 8.0)))
                fixture.module.actions.post(DamageAction(fixture.context(fixture.stranger), source, 7.0))
                assertEquals(1, fixture.module.actions.drainPosted())
                assertEquals(listOf(source, source), observed)
                assertEquals(85.0, handle.getComponent(Health::class.java)?.health)
                fixture.installer.permissions.add("fornaxcitadel.admin")
                assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(RemoveAction(
                    fixture.context(fixture.installer), source)))
                assertFalse(handle.isCurrent)
            }
        }
    }

    @Test
    fun `commands place on the selected half and inspect and remove through its counterpart`() {
        for (selectLeft in listOf(false, true)) {
            fixture().use { fixture ->
                val (left, right) = fixture.doubleChest()
                val selected = if (selectLeft) left else right
                val other = if (selectLeft) right else left
                fixture.installer.target = selected
                fixture.installer.permissions.addAll(listOf("fornaxcitadel.admin", "fornaxcitadel.info"))
                val messages = PaperMessages.read(YamlConfiguration().apply {
                    set("commands.info.health", "HP={health}")
                })
                val command = CitadelCommand(fixture.plugin, fixture.manager, fixture.module,
                    fixture.materials, fixture.citadelTypes, messages) {}
                command.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("place", "reinforced"))
                val handle = assertNotNull(fixture.module.citadels.findProtecting(other.testPosition()))
                assertEquals(selected.testPosition(), handle.position)
                fixture.installer.target = other
                command.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("info"))
                val text = fixture.installer.chatMessages.filterIsInstance<Component>()
                    .map(PlainTextComponentSerializer.plainText()::serialize)
                assertTrue("HP=100.0" in text)
                command.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("remove"))
                assertFalse(handle.isCurrent)
            }
        }
    }

    @Test
    fun `explosion removes both halves from vanilla destruction and damages reinforcement once`() {
        for (selectLeft in listOf(false, true)) {
            fixture().use { fixture ->
                val (left, right) = fixture.doubleChest()
                val selected = if (selectLeft) left else right
                val other = if (selectLeft) right else left
                val handle = fixture.placeExact(selected)
                var hits = 0
                fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
                    override val actionClass = DamageAction::class.java
                    override fun validate(action: DamageAction): ActionValidator.Result {
                        hits++
                        return ActionValidator.Result.Neutral
                    }
                })
                val entity = paperProxy<Entity>("exploding entity") { method, _ ->
                    when (method.name) {
                        "getUniqueId" -> UUID(30, 40)
                        "getType" -> EntityType.CREEPER
                        else -> unsupported(method)
                    }
                }
                val event = EntityExplodeEvent(entity, selected.location,
                    mutableListOf(other, selected), 1.0f, ExplosionResult.DESTROY)
                dispatch(fixture.explosionListener, event, fixture.plugin)
                assertTrue(event.blockList().isEmpty())
                assertEquals(1, hits)
                assertEquals(60.0, handle.getComponent(Health::class.java)?.health)
                val piston = BlockPistonExtendEvent(fixture.block(7, 64, 5), listOf(other), BlockFace.WEST)
                dispatch(fixture.environmentListener, piston, fixture.plugin)
                assertTrue(piston.isCancelled)
            }
        }
    }

    @Test
    fun `unavailable chest partner rejects placement and breaking without loading chunks`() {
        fixture().use { fixture ->
            val (left, right) = fixture.doubleChest(x = 16, facing = BlockFace.SOUTH)
            assertEquals(right.testPosition(), fixture.materialPlacement.getResponsiblePosition(right.testPosition()))
            assertFailsWith<CitadelResolutionUnavailableException> {
                fixture.module.citadels.findProtecting(right.testPosition())
            }
            val breaking = BlockBreakEvent(right, fixture.stranger.player)
            dispatch(fixture.breakingListener, breaking, fixture.plugin)
            assertTrue(breaking.isCancelled)
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(PlaceAction(
                fixture.context(fixture.installer), right.testPosition(), PaperCitadelTypes.REINFORCED)))
            assertFalse(fixture.manager.isChunkLoaded(fixture.worldId, 1, 0))
            assertFalse(1 to 0 in fixture.loadedWorldChunks)
            fixture.loadedWorldChunks.add(1 to 0)
            assertEquals(right.testPosition(), fixture.materialPlacement.getResponsiblePosition(right.testPosition()))
            assertFailsWith<CitadelResolutionUnavailableException> {
                fixture.module.citadels.findProtecting(right.testPosition())
            }
            fixture.loadChunk(1, 0)
            assertEquals(right.testPosition(), fixture.materialPlacement.getResponsiblePosition(right.testPosition()))
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(PlaceAction(
                fixture.context(fixture.installer), right.testPosition(), PaperCitadelTypes.REINFORCED)))
            assertNotNull(fixture.materialPlacement.findExact(right))
            assertNull(fixture.materialPlacement.findExact(left))
        }
    }

    @Test
    fun `chest rules preserve the default whitelist and leave shelves and pistons independent`() {
        PaperTestFixture(permissions = true).use { fixture ->
            for (material in listOf(Material.CHEST, Material.TRAPPED_CHEST) + copperChestMaterials) {
                val (_, right) = fixture.doubleChest(leftMaterial = material)
                assertEquals(ActionResult.DENIED, fixture.module.actions.execute(PlaceAction(
                    fixture.context(fixture.installer), right.testPosition(), PaperCitadelTypes.REINFORCED)))
            }
            val defaultRule = fixture.materialPlacement.getRule(Key("minecraft", "stone"))
            for (material in Material.entries.filter { !it.isLegacy && it.name.endsWith("_SHELF") } +
                listOf(Material.PISTON, Material.STICKY_PISTON, Material.PISTON_HEAD, Material.MOVING_PISTON)) {
                assertSame(defaultRule, fixture.materialPlacement.getRule(Key("minecraft", material.name.lowercase())))
            }
            val validator = ReinforceableBlockValidator(fixture.server)
            for (piston in listOf(Material.PISTON, Material.STICKY_PISTON, Material.PISTON_HEAD, Material.MOVING_PISTON)) {
                assertFalse(validator.isAllowed(piston))
            }
        }
    }

    private fun fixture() = PaperTestFixture(permissions = true,
        placementSettings = PlacementSettings(BlockListType.BLACKLIST, emptySet()))

    private object TestCommand : Command("citadel") {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean =
            error("Only the command executor is exercised")
    }
}
