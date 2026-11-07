package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.ExplosionResult
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.paper.modules.placement.BlockListType
import ru.fornax.fornaxcitadel.paper.modules.placement.DefaultMaterialPlacementRule
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.placement.PlacementSettings
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultipartReinforcementTest {
    @Test
    fun `item placement through either half creates one reinforcement and consumes one item`() {
        for (bed in listOf(false, true)) {
            fixture().use { fixture ->
                val (first, second) = if (bed) fixture.bed() else fixture.door()
                val responsible = if (bed) second else first
                val clicked = if (bed) first else second
                fixture.installer.held = fixture.reinforcementStack(amount = 3)
                val event = fixture.interact(clicked)

                dispatch(fixture.placementListener, event, fixture.plugin)

                assertNotNull(fixture.manager.findCitadel(fixture.worldId, responsible.x, responsible.y, responsible.z))
                assertNull(fixture.manager.findCitadel(fixture.worldId, clicked.x, clicked.y, clicked.z))
                assertEquals(2, fixture.installer.held.amount)
                assertEquals(Event.Result.DENY, event.useInteractedBlock())
                for (part in listOf(first, second)) {
                    dispatch(fixture.placementListener, fixture.interact(part), fixture.plugin)
                }
                assertEquals(2, fixture.installer.held.amount)
            }
        }
    }

    @Test
    fun `raw and queued actions keep original coordinates and reach the linked reinforcement`() {
        fixture().use { fixture ->
            val (lower, upper) = fixture.door()
            val source = upper.testPosition()
            val observed = mutableListOf<CitadelPosition>()
            fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction): ActionValidator.Result {
                    observed.add(action.position)
                    return ActionValidator.Result.Neutral
                }
            })
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(PlaceAction(
                fixture.context(fixture.installer), source, PaperCitadelTypes.REINFORCED)))
            val handle = assertNotNull(fixture.module.citadels.findProtecting(source))
            assertEquals(lower.testPosition(), handle.position)

            val direct = DamageAction(fixture.context(fixture.stranger), source, 8.0)
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(direct))
            fixture.module.actions.post(DamageAction(fixture.context(fixture.stranger), source, 7.0))
            assertEquals(1, fixture.module.actions.drainPosted())
            assertEquals(listOf(source, source), observed)
            assertEquals(source, direct.position)
            assertEquals(85.0, handle.getComponent(Health::class.java)?.health)

            fixture.installer.permissions.add("fornaxcitadel.admin")
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(RemoveAction(
                fixture.context(fixture.installer), source)))
            assertFalse(handle.isCurrent)
        }
    }

    @Test
    fun `linked damage uses target policy and creative breaking still cancels the removal attempt`() {
        fixture().use { fixture ->
            val (lower, upper) = fixture.door()
            val handle = fixture.placeExact(lower, PaperCitadelTypes.INVULNERABLE)
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(DamageAction(
                fixture.context(fixture.stranger), upper.testPosition(), 100.0)))
            assertEquals(100.0, handle.getComponent(Health::class.java)?.health)

            fixture.stranger.gameMode = GameMode.CREATIVE
            val first = BlockBreakEvent(upper, fixture.stranger.player)
            dispatch(fixture.breakingListener, first, fixture.plugin)
            assertTrue(first.isCancelled)
            assertFalse(handle.isCurrent)
            val next = BlockBreakEvent(upper, fixture.stranger.player)
            dispatch(fixture.breakingListener, next, fixture.plugin)
            assertFalse(next.isCancelled)
        }
    }

    @Test
    fun `old reinforcement on either half blocks duplicates and two old records keep local priority`() {
        fixture().use { fixture ->
            val (lower, upper) = fixture.door()
            val legacyUpper = fixture.placeExact(upper)
            assertEquals(legacyUpper.runtimeId,
                assertNotNull(fixture.module.citadels.findProtecting(lower.testPosition())).runtimeId)
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(PlaceAction(
                fixture.context(fixture.installer), lower.testPosition(), PaperCitadelTypes.REINFORCED)))
            val legacyLower = fixture.placeExact(lower)
            assertEquals(legacyLower.runtimeId,
                assertNotNull(fixture.module.citadels.findProtecting(lower.testPosition())).runtimeId)
            assertEquals(legacyUpper.runtimeId,
                assertNotNull(fixture.module.citadels.findProtecting(upper.testPosition())).runtimeId)

            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(DamageAction(
                fixture.context(fixture.stranger), upper.testPosition(), 100.0)))
            assertFalse(legacyUpper.isCurrent)
            assertEquals(100.0, legacyLower.getComponent(Health::class.java)?.health)
            assertEquals(legacyLower.runtimeId,
                assertNotNull(fixture.module.citadels.findProtecting(upper.testPosition())).runtimeId)
        }
    }

    @Test
    fun `commands place inspect and remove through the selected noncanonical half`() {
        fixture().use { fixture ->
            val (foot, head) = fixture.bed()
            fixture.installer.target = foot
            fixture.installer.permissions.addAll(listOf("fornaxcitadel.admin", "fornaxcitadel.info"))
            val messages = PaperMessages.read(YamlConfiguration().apply {
                set("commands.info.health", "HP={health}")
            })
            val command = CitadelCommand(fixture.plugin, fixture.manager, fixture.module,
                fixture.materials, fixture.citadelTypes, messages) {}
            command.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("place", "reinforced"))
            val handle = assertNotNull(fixture.module.citadels.findProtecting(foot.testPosition()))
            assertEquals(head.testPosition(), handle.position)
            command.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("info"))
            val text = fixture.installer.chatMessages.filterIsInstance<Component>()
                .map(PlainTextComponentSerializer.plainText()::serialize)
            assertTrue("HP=100.0" in text)
            command.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("remove"))
            assertFalse(handle.isCurrent)
        }
    }

    @Test
    fun `placement validators examine the responsible material`() {
        PaperTestFixture(permissions = true).use { fixture ->
            val source = fixture.block()
            val target = fixture.block(3, 64, 2)
            fixture.material(Material.SAND, target.testPosition())
            fixture.materialPlacement.register(Key("minecraft", "stone"),
                object : DefaultMaterialPlacementRule(fixture.materialPlacement::findExact) {
                    override fun getResponsibleBlock(block: Block): Block = target
                    override fun getRelatedBlock(block: Block): Block = target
                })
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(PlaceAction(
                fixture.context(fixture.installer), source.testPosition(), PaperCitadelTypes.REINFORCED)))
            assertNull(fixture.manager.findCitadel(fixture.worldId, target.x, target.y, target.z))
        }
    }

    @Test
    fun `missing linked chunk prevents breaking placement and environmental destruction`() {
        fixture().use { fixture ->
            val (foot, _) = fixture.bed(x = 15)
            val breaking = BlockBreakEvent(foot, fixture.stranger.player)
            dispatch(fixture.breakingListener, breaking, fixture.plugin)
            assertTrue(breaking.isCancelled)
            assertFalse(fixture.breakingService.canStartBreaking(fixture.stranger.player, foot))
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(PlaceAction(
                fixture.context(fixture.installer), foot.testPosition(), PaperCitadelTypes.REINFORCED)))
            val burn = BlockBurnEvent(foot, null)
            dispatch(fixture.environmentListener, burn, fixture.plugin)
            assertTrue(burn.isCancelled)
            assertFalse(fixture.manager.isChunkLoaded(fixture.worldId, 1, 0))
        }
    }

    @Test
    fun `both explosion halves are protected and one reinforcement receives one damage action`() {
        fixture().use { fixture ->
            val (lower, upper) = fixture.door()
            val handle = fixture.placeExact(lower)
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
            val event = EntityExplodeEvent(entity, lower.location,
                mutableListOf(upper, lower), 1.0f, ExplosionResult.DESTROY)
            dispatch(fixture.explosionListener, event, fixture.plugin)

            assertTrue(event.blockList().isEmpty())
            assertEquals(1, hits)
            assertEquals(60.0, handle.getComponent(Health::class.java)?.health)
        }
    }

    @Test
    fun `environment protects the unreinforced half while ordinary neighboring blocks remain available`() {
        fixture().use { fixture ->
            val (foot, head) = fixture.bed()
            fixture.placeExact(head)
            val burn = BlockBurnEvent(foot, null)
            val ordinaryBurn = BlockBurnEvent(fixture.block(8, 64, 8), null)
            val flow = BlockFromToEvent(fixture.block(4, 64, 3), foot)
            val piston = BlockPistonExtendEvent(fixture.block(3, 64, 4), listOf(foot), BlockFace.EAST)
            dispatch(fixture.environmentListener, burn, fixture.plugin)
            dispatch(fixture.environmentListener, ordinaryBurn, fixture.plugin)
            dispatch(fixture.environmentListener, flow, fixture.plugin)
            dispatch(fixture.environmentListener, piston, fixture.plugin)
            assertTrue(burn.isCancelled)
            assertFalse(ordinaryBurn.isCancelled)
            assertTrue(flow.isCancelled)
            assertTrue(piston.isCancelled)
        }
    }

    @Test
    fun `rule failure cancels physical breaking without damaging or replacing a block`() {
        fixture().use { fixture ->
            fixture.place()
            fixture.materialPlacement.register(Key("minecraft", "stone"),
                object : DefaultMaterialPlacementRule(fixture.materialPlacement::findExact) {
                    override fun getReinforcementProtecting(block: Block) = error("Rule lookup failed")
                })
            val event = BlockBreakEvent(fixture.block(), fixture.stranger.player)
            dispatch(fixture.breakingListener, event, fixture.plugin)
            assertTrue(event.isCancelled)
            assertEquals(100.0, fixture.health())
            assertEquals(0, fixture.vanillaBlockChanges)
        }
    }

    private fun fixture() = PaperTestFixture(permissions = true,
        placementSettings = PlacementSettings(BlockListType.BLACKLIST, emptySet()))

    private fun PaperTestFixture.interact(block: Block): PlayerInteractEvent = PlayerInteractEvent(
        installer.player, Action.RIGHT_CLICK_BLOCK, installer.held, block, BlockFace.UP, EquipmentSlot.HAND)

    private object TestCommand : Command("citadel") {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean =
            error("Only the command executor is exercised")
    }
}
