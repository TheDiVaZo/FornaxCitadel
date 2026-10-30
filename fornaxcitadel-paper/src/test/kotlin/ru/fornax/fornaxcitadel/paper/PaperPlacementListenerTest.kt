package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.util.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaperPlacementListenerTest {
    @Test
    fun `marked main hand placement consumes once and duplicate placement consumes nothing`() {
        val fixture = PaperTestFixture(permissions = true)
        val held = fixture.markedStack(amount = 2)
        fixture.installer.held = held
        val placed = fixture.interact()

        dispatch(fixture.placementListener, placed, fixture.plugin)

        assertEquals(1, held.amount)
        assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(Event.Result.DENY, placed.useInteractedBlock())
        assertEquals(Event.Result.DENY, placed.useItemInHand())
        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
        assertEquals(1, held.amount)
    }

    @Test
    fun `off hand unmarked and explicitly denied item interactions do not place`() {
        val fixture = PaperTestFixture()
        fixture.installer.held = fixture.markedStack()
        dispatch(fixture.placementListener, fixture.interact(EquipmentSlot.OFF_HAND), fixture.plugin)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.installer.held.amount)

        val denied = fixture.interact().apply { setUseItemInHand(Event.Result.DENY) }
        dispatch(fixture.placementListener, denied, fixture.plugin)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.installer.held.amount)

        fixture.installer.held = TestItemStack(Material.AMETHYST_SHARD, 2)
        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.installer.held.amount)
    }

    @Test
    fun `vanilla block no-op permits marked item while creative placement keeps its stack`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.installer.held = fixture.markedStack()
        fixture.installer.gameMode = GameMode.CREATIVE
        val event = fixture.interact().apply { setUseInteractedBlock(Event.Result.DENY) }

        dispatch(fixture.placementListener, event, fixture.plugin)

        assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.installer.held.amount)
    }

    @Test
    fun `placement permission and admin type rejection preserve the item stack`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.installer.held = fixture.markedStack()
        fixture.installer.permissions.remove("fornaxcitadel.place")
        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.installer.held.amount)

        fixture.installer.permissions.add("fornaxcitadel.place")
        fixture.installer.held = fixture.markedStack(PaperCitadelTypes.INVULNERABLE)
        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.installer.held.amount)
    }

    @Test
    fun `third party materials become placement items only after an explicit definition binding`() {
        val fixture = PaperTestFixture(permissions = true)
        val material = TestMaterialAdapter(Key("thirdparty", "reinforcement"))
        assertTrue(fixture.materials.register("thirdparty", TestMaterialRegistry(material)))
        for (stack in listOf(TestItemStack(Material.STONE, 2), material.createItemStack(2))) {
            fixture.installer.held = stack
            val ordinary = fixture.interact()
            val blockUse = ordinary.useInteractedBlock()
            val itemUse = ordinary.useItemInHand()

            dispatch(fixture.placementListener, ordinary, fixture.plugin)

            assertEquals(2, stack.amount)
            assertEquals(blockUse, ordinary.useInteractedBlock())
            assertEquals(itemUse, ordinary.useItemInHand())
            assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        }

        fixture.placementService.registerMaterial(material.getKey(), PaperCitadelTypes.REINFORCED)
        fixture.placementService.registerMaterial(material.getKey(), PaperCitadelTypes.REINFORCED)
        assertFailsWith<IllegalArgumentException> {
            fixture.placementService.registerMaterial(material.getKey(), PaperCitadelTypes.INVULNERABLE)
        }
        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)

        val placed = assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(PaperCitadelTypes.REINFORCED, placed.getComponent(CitadelKind::class.java)?.definitionKey)
        assertEquals(1, fixture.installer.held.amount)
        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
        assertEquals(1, fixture.installer.held.amount)
    }

    @Test
    fun `third party materials retain placement permission and definition privilege checks`() {
        for (definition in listOf(PaperCitadelTypes.REINFORCED, PaperCitadelTypes.INVULNERABLE)) {
            val fixture = PaperTestFixture(permissions = true)
            val material = TestMaterialAdapter(Key("thirdparty", "reinforcement"))
            assertTrue(fixture.materials.register("thirdparty", TestMaterialRegistry(material)))
            fixture.placementService.registerMaterial(material.getKey(), definition)
            fixture.installer.held = material.createItemStack(2)
            if (definition == PaperCitadelTypes.REINFORCED) {
                fixture.installer.permissions.remove("fornaxcitadel.place")
            }

            dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)

            assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
            assertEquals(2, fixture.installer.held.amount)
        }
    }

    @Test
    fun `held material identity must match even when both materials share a base item and definition`() {
        val fixture = PaperTestFixture(permissions = true)
        val first = TestMaterialAdapter(Key("thirdparty", "first"))
        val second = TestMaterialAdapter(Key("thirdparty", "second"))
        assertTrue(fixture.materials.register("thirdparty", TestMaterialRegistry(first, second)))
        fixture.placementService.registerMaterial(first.getKey(), PaperCitadelTypes.REINFORCED)
        fixture.placementService.registerMaterial(second.getKey(), PaperCitadelTypes.REINFORCED)
        val original = first.createItemStack(2)
        fixture.installer.held = original
        val stale = fixture.interact()
        val blockUse = stale.useInteractedBlock()
        val itemUse = stale.useItemInHand()
        fixture.installer.held = second.createItemStack(2)

        dispatch(fixture.placementListener, stale, fixture.plugin)

        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, original.amount)
        assertEquals(2, fixture.installer.held.amount)
        assertEquals(blockUse, stale.useInteractedBlock())
        assertEquals(itemUse, stale.useItemInHand())
    }

    private fun PaperTestFixture.interact(hand: EquipmentSlot = EquipmentSlot.HAND) = PlayerInteractEvent(
        installer.player, Action.RIGHT_CLICK_BLOCK, installer.held, block(), BlockFace.UP, hand
    )
}
