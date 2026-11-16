package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.type.Chest
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.modules.acid.*
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.placement.PlacementSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.BlockListType
import kotlin.test.*

class AcidIntegrationTest {
    @Test
    fun `non-container inventory holders keep their contents`() = fixture().use { f ->
        acid(f)
        val target = f.block(2, 64, 2)
        f.material(Material.CHISELED_BOOKSHELF, target.testPosition())
        reinforce(f, target)
        f.inventories[Triple(target.x, target.y, target.z)] = arrayOf(TestItemStack(Material.BOOK, 1))
        assertEquals(AcidResult.Activated(1), activate(f))
        assertEquals(listOf(Material.IRON_INGOT, Material.BOOK, Material.CHISELED_BOOKSHELF), f.droppedItems.map { it.second.type })
    }

    @Test
    fun `a door or bed is one target with all parts checked before breaking`() {
        for (door in listOf(false, true)) fixture().use { f ->
            acid(f)
            val (first, second) = if (door) f.door(2, 64, 2) else f.bed(2, 64, 2)
            reinforce(f, first)
            f.afterEvent = { event -> if (event is BlockBreakEvent && event.block === second) event.isCancelled = true }
            assertEquals(AcidResult.Activated(0), activate(f))
            assertEquals(1, f.vanillaBlockChanges)
            assertNotNull(f.module.citadels.findProtecting(first.testPosition()))
            assertNull(f.module.citadels.findProtecting(f.position))
            assertFalse(first.isEmpty || second.isEmpty)
            assertEquals(listOf(Material.IRON_INGOT), f.droppedItems.map { it.second.type })
            acid(f)
            f.afterEvent = {}
            f.calledEvents.clear()
            assertEquals(AcidResult.Activated(1), activate(f))
            assertTrue(first.isEmpty)
            assertTrue(second.isEmpty)
            assertNull(f.module.citadels.findProtecting(first.testPosition()))
            assertEquals(2, f.calledEvents.filterIsInstance<BlockBreakEvent>().size)
            val events = f.calledEvents.filterIsInstance<AcidBlockDissolveEvent>()
            assertEquals(listOf(first, second), events.map { it.block })
            assertEquals(1, f.droppedItems.count { it.second.type == if (door) Material.OAK_DOOR else Material.RED_BED })
        }
    }

    @Test
    fun `linked unloaded chunk prevents mutation even for a directly loaded target`() =
        fixture().use { f ->
            val source = f.block(14, 64, 2)
            acid(f, source)
            f.loadChunk(1, 0)
            val (foot, head) = f.bed(15, 64, 2)
            reinforce(f, foot)
            f.manager.unloadChunk(f.worldId, 1, 0).join()
            f.loadedWorldChunks.remove(1 to 0)
            assertEquals(AcidResult.Failed(AcidFailure.CHUNK_UNAVAILABLE), f.acidService!!.activate(f.installer.player, source))
            assertEquals(0, f.vanillaBlockChanges)
            assertFalse(head.isEmpty)
            assertTrue(f.calledEvents.isEmpty())
        }

    @Test
    fun `double chest keeps the other half and returns only local contents once`() =
        fixture().use { f ->
            acid(f)
            val (left, right) = f.doubleChest(2, 64, 2)
            reinforce(f, left)
            f.inventories[Triple(left.x, left.y, left.z)] = arrayOf(TestItemStack(Material.EMERALD, 7))
            f.inventories[Triple(right.x, right.y, right.z)] = arrayOf(TestItemStack(Material.DIAMOND, 9))
            assertEquals(AcidResult.Activated(1), activate(f))
            assertTrue(left.isEmpty)
            assertEquals(Material.CHEST, right.type)
            assertEquals(Chest.Type.SINGLE, (right.blockData as Chest).type)
            assertEquals(9, f.inventories[Triple(right.x, right.y, right.z)]!!.single()!!.amount)
            assertEquals(7, f.droppedItems.single { it.second.type == Material.EMERALD }.second.amount)
            assertEquals(1, f.droppedItems.count { it.second.type == Material.CHEST })
            assertFalse(f.droppedItems.any { it.second.type == Material.DIAMOND })
        }

    @Test
    fun `a chest source and its selected partner are destroyed sequentially with separate inventories`() =
        fixture(AcidSettings(types = listOf(AcidType("chest", Material.CHEST, 1, 1.0)))).use { f ->
            // Reinforce separately before joining, as with independent existing chest records.
            reinforce(f, f.block(1, 64, 2))
            reinforce(f, f.block(2, 64, 2))
            val (source, target) = f.doubleChest(1, 64, 2)
            f.inventories[Triple(source.x, source.y, source.z)] = arrayOf(TestItemStack(Material.EMERALD, 7))
            f.inventories[Triple(target.x, target.y, target.z)] = arrayOf(TestItemStack(Material.DIAMOND, 9))
            f.beforeEvent = { if (it is AcidBlockDissolveEvent) {
                assertTrue(source.isEmpty)
                assertEquals(Chest.Type.SINGLE, (target.blockData as Chest).type)
            } }

            assertEquals(AcidResult.Activated(1), f.acidService!!.activate(f.installer.player, source))
            assertTrue(source.isEmpty && target.isEmpty)
            val items = f.droppedItems.map { it.second }
            assertEquals(7, items.single { it.type == Material.EMERALD }.amount)
            assertEquals(9, items.single { it.type == Material.DIAMOND }.amount)
            assertEquals(1, items.count { it.type == Material.CHEST })
            assertEquals(1, items.count { it.type == Material.IRON_INGOT })
        }

    @Test
    fun `a selected chunk becoming unavailable skips its target after source consumption`() = fixture().use { f ->
        f.loadChunk(1, 0)
        val source = f.block(15, 64, 2)
        f.material(Material.DIAMOND_BLOCK, source.testPosition())
        reinforce(f, source)
        val first = source.getRelative(org.bukkit.block.BlockFace.UP)
        val last = source.getRelative(org.bukkit.block.BlockFace.EAST)
        reinforce(f, first)
        reinforce(f, last)
        f.afterEvent = { if (it is AcidBlockDissolveEvent && it.block === first) {
            f.manager.unloadChunk(f.worldId, 1, 0).join()
            f.loadedWorldChunks.remove(1 to 0)
        } }

        assertEquals(AcidResult.Activated(1), f.acidService!!.activate(f.installer.player, source))
        assertTrue(source.isEmpty && first.isEmpty)
        assertFalse(last.isEmpty)
        f.loadChunk(1, 0)
        assertNotNull(f.module.citadels.findProtecting(last.testPosition()))
    }

    @Test
    fun `container contents are read after callbacks and survive disabled block drops`() {
        for (mutate in listOf(false, true)) PaperTestFixture(permissions = true,
            acidSettings = AcidSettings(drops = AcidDrops(targetBlock = false))).use { f ->
            acid(f)
            val target = f.block(2, 64, 2)
            reinforce(f, target)
            val key = Triple(target.x, target.y, target.z)
            f.inventories[key] = arrayOf(TestItemStack(Material.EMERALD, 7))
            if (mutate) f.afterEvent = { if (it is AcidBlockDissolveEvent) f.inventories[key] = arrayOf(TestItemStack(Material.EMERALD, 8)) }
            assertEquals(AcidResult.Activated(1), activate(f))
            assertFalse(f.droppedItems.any { it.second.type == Material.STONE })
            assertEquals(if (mutate) 8 else 7, f.droppedItems.single { it.second.type == Material.EMERALD }.second.amount)
            assertTrue(f.inventories[key]!!.all { it == null })
        }
    }

    @Test
    fun `ordinary block drops remain cancelable without duplicating reinforcement refunds`() =
        PaperTestFixture(permissions = true, acidSettings = AcidSettings()).use { f ->
            acid(f)
            reinforce(f, f.block(2, 64, 2))
            f.afterEvent = { if (it is BlockDropItemEvent) it.isCancelled = true }
            assertEquals(AcidResult.Activated(1), activate(f))
            assertEquals(listOf(Material.IRON_INGOT), f.droppedItems.map { it.second.type })
        }

    @Test
    fun `multipart source is consumed as a whole without dissolve events after activation is allowed`() {
        for (material in listOf(Material.OAK_DOOR, Material.RED_BED))
            fixture(AcidSettings(types = listOf(AcidType("multipart", material, 1, 1.0)))).use { f ->
                val (first, second) = if (material == Material.OAK_DOOR) f.door() else f.bed(1, 64, 2)
                reinforce(f, second)
                val source = assertNotNull(f.module.citadels.findProtecting(first.testPosition()))
                val anchor = if (first.testPosition() == source.position) first else second
                val clicked = if (anchor === first) second else first
                val target = anchor.getRelative(org.bukkit.block.BlockFace.NORTH)
                reinforce(f, target)
                f.afterEvent = { if (it is AcidBlockActivateEvent) it.isCancelled = true }
                assertEquals(AcidResult.Failed(AcidFailure.CANCELLED), f.acidService!!.activate(f.installer.player, clicked))
                assertTrue(source.isCurrent)
                assertEquals(0, f.vanillaBlockChanges)
                assertTrue(f.droppedItems.isEmpty())
                f.afterEvent = { if (it is BlockBreakEvent && it.block in listOf(first, second)) it.isCancelled = true }
                f.calledEvents.clear()

                assertEquals(AcidResult.Activated(1), f.acidService.activate(f.installer.player, clicked))

                assertTrue(first.isEmpty)
                assertTrue(second.isEmpty)
                val events = f.calledEvents.filterIsInstance<AcidBlockDissolveEvent>()
                assertEquals(listOf(target), events.map { it.block })
                assertTrue(events.all { it.acidPosition == source.position && it.acidType.material == material })
            }
    }

    @Test
    fun `suppressing either target part suppresses the single multipart item`() {
        for (vetoFirst in listOf(false, true)) fixture().use { f ->
            acid(f)
            val (first, second) = f.door(2, 64, 2)
            reinforce(f, first)
            val veto = if (vetoFirst) first else second
            f.afterEvent = { event -> if (event is BlockBreakEvent && event.block === veto) event.isDropItems = false }
            assertEquals(AcidResult.Activated(1), activate(f))
            assertTrue(first.isEmpty && second.isEmpty)
            assertEquals(listOf(Material.IRON_INGOT), f.droppedItems.map { it.second.type })
        }
    }

    @Test
    fun `info completion permissions usage and activation share the root command`() =
        PaperTestFixture(permissions = true, acidSettings = AcidSettings()).use { f ->
            acid(f)
            reinforce(f, f.block(2, 64, 2))
            f.installer.target = f.block()
            f.installer.permissions.add("fornaxcitadel.info")
            val command = CitadelCommand(f.plugin, f.manager, f.module, f.materials, f.citadelTypes) {}
            command.acid = f.acidService
            assertEquals(listOf("acid"), command.onTabComplete(f.installer.player, TestCommand, "citadel", arrayOf("ac")))
            assertEquals(listOf("info"), command.onTabComplete(f.installer.player, TestCommand, "citadel", arrayOf("acid", "")))
            command.onCommand(f.installer.player, TestCommand, "citadel", arrayOf("acid", "info"))
            val messages = f.installer.chatMessages.filterIsInstance<Component>().map(PlainTextComponentSerializer.plainText()::serialize)
            assertEquals(8, messages.size)
            assertTrue(messages.any { "GOLD_BLOCK" in it })
            assertTrue(messages.any { "installer" in it })
            assertEquals(0, f.vanillaBlockChanges)
            assertTrue(f.calledEvents.isEmpty())
            command.onCommand(f.installer.player, TestCommand, "citadel", arrayOf("acid", "north"))
            assertEquals(0, f.vanillaBlockChanges)
            f.installer.permissions.remove("fornaxcitadel.acid")
            command.onCommand(f.installer.player, TestCommand, "citadel", arrayOf("acid"))
            assertEquals(0, f.vanillaBlockChanges)
            f.installer.permissions.add("fornaxcitadel.acid")
            command.onCommand(f.installer.player, TestCommand, "citadel", arrayOf("acid"))
            assertTrue(f.block().isEmpty)
        }

    @Test
    fun `configured ray distance and unloaded rays never force chunk loading`() =
        PaperTestFixture(permissions = true, acidSettings = AcidSettings(maxDistance = 2)).use { f ->
            val far = f.block(1, 64, 6)
            acid(f, far)
            f.installer.target = far
            assertNull(f.acidService!!.findTarget(f.installer.player))
            f.installer.location = org.bukkit.Location(f.world, 15.5, 64.5, 2.5)
            f.installer.target = f.block(17, 64, 2)
            assertFailsWith<ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException> {
                f.acidService.findTarget(f.installer.player)
            }
        }

    private fun acid(f: PaperTestFixture, block: Block = f.block()) {
        f.material(Material.GOLD_BLOCK, block.testPosition())
        reinforce(f, block)
    }
    private fun reinforce(f: PaperTestFixture, block: Block) {
        val type = PaperCitadelTypes.REINFORCED
        assertEquals(ActionResult.SUCCESS, f.module.actions.execute(PlaceAction(f.context(f.installer), block.testPosition(), type)))
    }
    private fun activate(f: PaperTestFixture) = f.acidService!!.activate(f.installer.player, f.block())
    private fun fixture(settings: AcidSettings = AcidSettings()) = PaperTestFixture(permissions = true,
        placementSettings = PlacementSettings(BlockListType.BLACKLIST, emptySet()), acidSettings = settings)
    private object TestCommand : Command("citadel") {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>) = error("unused")
    }
}
