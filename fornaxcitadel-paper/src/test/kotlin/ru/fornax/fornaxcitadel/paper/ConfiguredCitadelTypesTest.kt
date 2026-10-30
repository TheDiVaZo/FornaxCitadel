package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.TextComponent
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.material.MaterialAdapter
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfiguredCitadelTypesTest {
    @Test
    fun `custom identifiers register independent simple and maturing health behaviors`() {
        val fixture = PaperTestFixture(permissions = true, citadelSettings = CUSTOM_TYPES)
        assertEquals(CUSTOM_TYPES.types.keys.toList(), fixture.citadelTypes.names)
        assertNull(fixture.citadelTypes.find("reinforced"))
        val handles = listOf("clay_guard", "titanium", "growing_wall").mapIndexed { index, name ->
            val key = assertNotNull(fixture.citadelTypes.find(name))
            val position = fixture.position.copy(x = index)
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(
                PlaceAction(fixture.context(fixture.installer), position, key)
            ))
            assertNotNull(fixture.manager.findCitadel(position.world, position.x, position.y, position.z))
        }

        assertEquals(13.0, handles[0].getComponent(Health::class.java)?.health)
        assertEquals(73.0, handles[1].getComponent(Health::class.java)?.health)
        assertNull(handles[0].getComponent(Maturing::class.java))
        assertNull(handles[1].getComponent(Maturing::class.java))
        val growing = handles[2]
        assertEquals(6.0, growing.getComponent(Health::class.java)?.health)
        assertNotNull(growing.getComponent(Maturing::class.java))
        assertTrue(growing.editComponent(Maturing::class.java) { it.startedAtMillis = 0L })
        assertEquals(48.0, fixture.module.health.getCurrentMaxHealth(fixture.worldId, 2, 64, 2))
        assertEquals(48.0, fixture.module.health.getCurrentHealth(fixture.worldId, 2, 64, 2))
    }

    @Test
    fun `installation items recognize configured keys and placement uses their definition`() {
        val fixture = PaperTestFixture(permissions = true, citadelSettings = CUSTOM_TYPES)
        fixture.installer.held = fixture.markedStack(PaperCitadelTypes.REINFORCED)
        assertNull(fixture.items.typeOf(fixture.installer.held))
        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.installer.held.amount)

        val key = assertNotNull(fixture.citadelTypes.find("clay_guard"))
        fixture.installer.held = fixture.markedStack(key)
        assertEquals(key, fixture.items.typeOf(fixture.installer.held))
        assertEquals(key, fixture.materials.getTypeOrNull(fixture.installer.held)?.getKey())

        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)

        val placed = assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(key, placed.getComponent(CitadelKind::class.java)?.definitionKey)
        assertEquals(13.0, fixture.health())
        assertEquals(1, fixture.installer.held.amount)
    }

    @Test
    fun `commands completions and info use the configured catalog without builtin identifiers`() {
        val fixture = PaperTestFixture(permissions = true, citadelSettings = CUSTOM_TYPES)
        fixture.installer.permissions.addAll(listOf("fornaxcitadel.admin", "fornaxcitadel.info"))
        fixture.installer.target = fixture.block()
        val commands = CitadelCommand(fixture.plugin, fixture.manager, fixture.module,
            fixture.materials, fixture.citadelTypes) {}
        assertEquals(CUSTOM_TYPES.types.keys.toList(),
            commands.onTabComplete(fixture.installer.player, TestCommand, "citadel", arrayOf("place", "")))
        assertEquals(listOf("growing_wall"),
            commands.onTabComplete(fixture.installer.player, TestCommand, "citadel", arrayOf("give", "grow")))

        commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("place", "TITANIUM"))
        val placed = assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(fixture.citadelTypes.find("titanium"), placed.getComponent(CitadelKind::class.java)?.definitionKey)
        assertEquals(73.0, fixture.health())
        commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("info"))

        val messages = fixture.installer.messages.filterIsInstance<TextComponent>().map { it.content() }
        assertTrue("Тип: titanium" in messages)
        assertTrue("Здоровье: 73.0 / 73.0" in messages)
        commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("remove"))
        commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("place", "reinforced"))
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
    }

    @Test
    fun `a custom unbreakable identifier requires admin installation and retains its damage policy`() {
        val fixture = PaperTestFixture(permissions = true, citadelSettings = CUSTOM_TYPES)
        val sanctuary = assertNotNull(fixture.citadelTypes.find("sanctuary"))
        assertTrue(fixture.citadelTypes.requiresAdmin(sanctuary))
        assertFalse(fixture.citadelTypes.requiresAdmin(assertNotNull(fixture.citadelTypes.find("clay_guard"))))
        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
            PlaceAction(fixture.context(fixture.installer), fixture.position, sanctuary)
        ))
        fixture.installer.held = fixture.markedStack(sanctuary)
        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.installer.held.amount)

        fixture.installer.permissions.add("fornaxcitadel.admin")
        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
        val placed = assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(sanctuary, placed.getComponent(CitadelKind::class.java)?.definitionKey)
        assertNull(placed.getComponent(Health::class.java))
        assertEquals(1, fixture.installer.held.amount)
        val breaking = BlockBreakEvent(fixture.block(), fixture.stranger.player)
        dispatch(fixture.breakingListener, breaking, fixture.plugin)
        assertTrue(breaking.isCancelled)
        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
            DamageAction(fixture.context(fixture.stranger), fixture.position, Double.MAX_VALUE)
        ))
        assertTrue(placed.isCurrent)
        assertEquals(0, fixture.vanillaBlockChanges)
    }

    @Test
    fun `a parsed vanilla item installs its configured type and replaces the old same-id installation item`() {
        val config = YamlConfiguration().apply {
            loadFromString("""
                citadel-types:
                  diamond_wall:
                    item: diamond
                    health:
                      behavior: simple
                      max: 37.0
            """.trimIndent())
        }
        val fixture = PaperTestFixture(permissions = true, citadelSettings = PaperSettings.read(config).citadelTypes)
        val definition = assertNotNull(fixture.citadelTypes.find("diamond_wall"))
        assertEquals(Key("minecraft", "diamond"), fixture.citadelTypes.itemKey(definition))
        fixture.installer.held = fixture.markedStack(definition)

        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)

        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.installer.held.amount)
        fixture.installer.held = TestItemStack(Material.DIAMOND, 2)
        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
        val placed = assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(definition, placed.getComponent(CitadelKind::class.java)?.definitionKey)
        assertEquals(37.0, fixture.health())
        assertEquals(1, fixture.installer.held.amount)
    }

    @Test
    fun `a configured third party item resolves after its provider registers without another placement binding`() {
        val config = YamlConfiguration().apply {
            loadFromString("""
                citadel-types:
                  steel_guard:
                    item: thirdparty:steel_plate
                    health:
                      behavior: simple
                      max: 29.0
            """.trimIndent())
        }
        val fixture = PaperTestFixture(permissions = true, citadelSettings = PaperSettings.read(config).citadelTypes)
        val definition = assertNotNull(fixture.citadelTypes.find("steel_guard"))
        val itemKey = Key("thirdparty", "steel_plate")
        val material = TestMaterialAdapter(itemKey)
        fixture.installer.held = material.createItemStack(2)
        assertEquals(itemKey, fixture.citadelTypes.itemKey(definition))

        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)

        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.installer.held.amount)
        assertTrue(fixture.materials.register("thirdparty", TestMaterialRegistry(material)))
        dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
        val placed = assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(definition, placed.getComponent(CitadelKind::class.java)?.definitionKey)
        assertEquals(29.0, fixture.health())
        assertEquals(1, fixture.installer.held.amount)
    }

    @Test
    fun `give resolves the configured item key and uses its factory while completion still shows type identifiers`() {
        val itemKey = Key("thirdparty", "steel_plate")
        val settings = CitadelTypesSettings(mapOf("steel_guard" to
            CitadelTypeSettings(itemKey, health = HealthBehaviorSettings.Simple(29.0))))
        val fixture = PaperTestFixture(permissions = true, citadelSettings = settings)
        fixture.installer.permissions.add("fornaxcitadel.admin")
        val requestedKeys = mutableListOf<Key>()
        val requestedAmounts = mutableListOf<Int>()
        val adapter = TestMaterialAdapter(itemKey)
        val factory = object : MaterialAdapter by adapter {
            override fun createItemStack(amount: Int): ItemStack {
                requestedAmounts.add(amount)
                return adapter.createItemStack(amount)
            }
        }
        val provider = TestMaterialRegistry(factory)
        val registry = object : MaterialRegistry by provider {
            override fun getType(key: Key): MaterialAdapter? {
                requestedKeys.add(key)
                return provider.getType(key)
            }
        }
        val commands = CitadelCommand(fixture.plugin, fixture.manager, fixture.module, registry, fixture.citadelTypes) {}
        assertEquals(listOf("steel_guard"),
            commands.onTabComplete(fixture.installer.player, TestCommand, "citadel", arrayOf("give", "steel")))

        commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("give", "steel_guard", "stranger", "3"))

        assertEquals(listOf(itemKey), requestedKeys)
        assertEquals(listOf(3), requestedAmounts)
        val received = fixture.stranger.addedItems.single()
        assertTrue(adapter.isConsists(received))
        assertEquals(3, received.amount)
        assertTrue(fixture.installer.addedItems.isEmpty())
    }

    private fun PaperTestFixture.interact(): PlayerInteractEvent = PlayerInteractEvent(
        installer.player, Action.RIGHT_CLICK_BLOCK, installer.held, block(), BlockFace.UP, EquipmentSlot.HAND
    )

    private object TestCommand : Command("citadel") {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean =
            error("Only the registered command executor is exercised")
    }

    private companion object {
        val CUSTOM_TYPES = CitadelTypesSettings(linkedMapOf(
            "clay_guard" to CitadelTypeSettings(Key("fornaxcitadel", "clay_guard"), health = HealthBehaviorSettings.Simple(13.0)),
            "titanium" to CitadelTypeSettings(Key("fornaxcitadel", "titanium"), health = HealthBehaviorSettings.Simple(73.0)),
            "growing_wall" to CitadelTypeSettings(Key("fornaxcitadel", "growing_wall"), health = HealthBehaviorSettings.Maturing(6.0, 48.0, 30)),
            "sanctuary" to CitadelTypeSettings(Key("fornaxcitadel", "sanctuary"), CitadelBreakPolicy.UNBREAKABLE)
        ))
    }
}
