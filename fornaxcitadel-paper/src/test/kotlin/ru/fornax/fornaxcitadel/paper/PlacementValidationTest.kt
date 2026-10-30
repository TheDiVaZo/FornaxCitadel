package ru.fornax.fornaxcitadel.paper

import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.modules.placement.BlockListType
import ru.fornax.fornaxcitadel.paper.modules.placement.PlacementBlockListValidator
import ru.fornax.fornaxcitadel.paper.modules.placement.PlacementSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.ReinforceableBlockValidator
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacementValidationTest {
    @Test
    fun `physical predicate rejects piston parts liquids air and items but accepts other solid blocks`() {
        val validator = ReinforceableBlockValidator(PaperTestFixture().server)
        for (material in listOf(Material.PISTON, Material.STICKY_PISTON, Material.PISTON_HEAD, Material.MOVING_PISTON,
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR, Material.WATER, Material.LAVA,
            Material.AMETHYST_SHARD, Material.DIAMOND)) {
            assertFalse(validator.isAllowed(material), material.name)
        }
        for (material in listOf(Material.STONE, Material.SAND, Material.CHEST, Material.BEDROCK,
            Material.OAK_PLANKS, Material.STONE_SLAB)) {
            assertTrue(validator.isAllowed(material), material.name)
        }
    }

    @Test
    fun `whitelist blacklist and empty lists use exact membership with unresolved materials treated as unlisted`() {
        val fixture = PaperTestFixture()
        val stone = fixture.block()
        val sand = fixture.block(3, 64, 2)
        fixture.material(Material.SAND, CitadelPosition(fixture.worldId, 3, 64, 2))
        val white = PlacementBlockListValidator(fixture.server, fixture.materials, BlockListType.WHITELIST, setOf(STONE))
        val black = PlacementBlockListValidator(fixture.server, fixture.materials, BlockListType.BLACKLIST, setOf(STONE))

        assertTrue(white.isAllowed(stone))
        assertFalse(white.isAllowed(sand))
        assertFalse(black.isAllowed(stone))
        assertTrue(black.isAllowed(sand))
        assertFalse(PlacementBlockListValidator(fixture.server, fixture.materials,
            BlockListType.WHITELIST, emptySet()).isAllowed(stone))
        assertTrue(PlacementBlockListValidator(fixture.server, fixture.materials,
            BlockListType.BLACKLIST, emptySet()).isAllowed(stone))

        val unresolved = TestMaterialRegistry()
        assertFalse(PlacementBlockListValidator(fixture.server, unresolved,
            BlockListType.WHITELIST, setOf(STONE)).isAllowed(stone))
        assertTrue(PlacementBlockListValidator(fixture.server, unresolved,
            BlockListType.BLACKLIST, setOf(STONE)).isAllowed(stone))
    }

    @Test
    fun `custom block providers may register after initialization and never inherit vanilla carrier membership`() {
        val customKey = Key("thirdparty", "reinforceable_stone")
        val fixture = PaperTestFixture(placementSettings = PlacementSettings(blocks = setOf(customKey)))
        val action = fixture.placeAction()
        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        val custom = TestMaterialAdapter(customKey, taggedBlock = fixture.block())

        assertTrue(fixture.materials.register("thirdparty", TestMaterialRegistry(custom)))

        assertTrue(PlacementBlockListValidator(fixture.server, fixture.materials,
            BlockListType.WHITELIST, setOf(customKey)).isAllowed(fixture.block()))
        assertFalse(PlacementBlockListValidator(fixture.server, fixture.materials,
            BlockListType.WHITELIST, setOf(STONE)).isAllowed(fixture.block()))
        assertTrue(PlacementBlockListValidator(fixture.server, fixture.materials,
            BlockListType.BLACKLIST, setOf(STONE)).isAllowed(fixture.block()))
        assertFalse(PlacementBlockListValidator(fixture.server, fixture.materials,
            BlockListType.BLACKLIST, setOf(customKey)).isAllowed(fixture.block()))
        assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(action))
        assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
    }

    @Test
    fun `registered placement validators protect direct posted command and item paths without consuming rejected items`() {
        val cases = listOf(
            Material.PISTON to PlacementSettings(BlockListType.BLACKLIST, emptySet()),
            Material.SAND to PlacementSettings(blocks = setOf(STONE))
        )
        for ((rejectedMaterial, settings) in cases) {
            val fixture = PaperTestFixture(permissions = true, placementSettings = settings)
            fixture.material(rejectedMaterial)
            val action = fixture.placeAction()
            // Permissions and physical placement rules are independent validators.
            assertEquals(ActionValidator.Result.Success, PaperPermissionValidator(fixture.server, fixture.citadelTypes).validate(action))
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))

            fixture.material(Material.STONE)
            fixture.module.actions.post(action)
            fixture.material(rejectedMaterial)
            assertEquals(1, fixture.module.actions.drainPosted(1))
            assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))

            fixture.installer.permissions.add("fornaxcitadel.admin")
            fixture.installer.target = fixture.block()
            val commands = CitadelCommand(fixture.plugin, fixture.manager, fixture.module, fixture.materials, fixture.citadelTypes) {}
            commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("place", "reinforced"))
            assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))

            fixture.installer.held = fixture.markedStack(amount = 2)
            val interaction = PlayerInteractEvent(fixture.installer.player, Action.RIGHT_CLICK_BLOCK,
                fixture.installer.held, fixture.block(), BlockFace.UP, EquipmentSlot.HAND)
            dispatch(fixture.placementListener, interaction, fixture.plugin)
            assertEquals(Event.Result.DENY, interaction.useItemInHand())
            assertEquals(2, fixture.installer.held.amount)
            assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))

            fixture.material(Material.STONE)
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(action))
            assertEquals(0, fixture.vanillaBlockChanges)
        }
    }

    @Test
    fun `both validators reject unavailable worlds chunks and invalid heights before accessing blocks`() {
        val fixture = PaperTestFixture()
        val validators: List<ActionValidator<PlaceAction>> = listOf(
            ReinforceableBlockValidator(fixture.server),
            PlacementBlockListValidator(fixture.server, fixture.materials, BlockListType.BLACKLIST, emptySet())
        )
        for (validator in validators) {
            fixture.worldAvailable = false
            assertEquals(ActionValidator.Result.Rejected, validator.validate(fixture.placeAction()))
            fixture.worldAvailable = true
            fixture.worldChunkLoaded = false
            assertEquals(ActionValidator.Result.Rejected, validator.validate(fixture.placeAction()))
            fixture.worldChunkLoaded = true

            for (position in listOf(
                CitadelPosition(UUID(9L, 9L), 1, 64, 2),
                CitadelPosition(fixture.worldId, 16, 64, 2),
                CitadelPosition(fixture.worldId, 1, -65, 2),
                CitadelPosition(fixture.worldId, 1, 320, 2),
                CitadelPosition(fixture.worldId, Int.MAX_VALUE, 64, 2)
            )) {
                assertEquals(ActionValidator.Result.Rejected, validator.validate(fixture.placeAction(position)))
            }
        }
        assertEquals(0, fixture.blockLookups)
    }

    @Test
    fun `explicitly listed sand and chests can be reinforced without the old durable block restriction`() {
        for (material in listOf(Material.SAND, Material.CHEST)) {
            val fixture = PaperTestFixture(permissions = true,
                placementSettings = PlacementSettings(blocks = setOf(Key("minecraft", material.name.lowercase()))))
            fixture.material(material)

            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(fixture.placeAction()))

            assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
            assertEquals(0, fixture.vanillaBlockChanges)
        }
    }

    private fun PaperTestFixture.placeAction(at: CitadelPosition = position): PlaceAction =
        PlaceAction(context(installer), at, PaperCitadelTypes.REINFORCED)

    private object TestCommand : Command("citadel") {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean =
            error("Only the registered command executor is exercised")
    }

    private companion object {
        val STONE = Key("minecraft", "stone")
    }
}
