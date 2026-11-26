package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.type.Chest
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.NamedActionSource
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.acid.AcidAction
import ru.fornax.fornaxcitadel.modules.health.AcidDestroyAction
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.config.PaperMessages
import ru.fornax.fornaxcitadel.paper.material.MaterialAdapter
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.acid.*
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.placement.PlacementSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.BlockListType
import java.util.UUID
import kotlin.test.*

class AcidIntegrationTest {
    @Test
    fun `system and addon acid actions preserve child attribution and complete after destruction`() {
        val addon = ActionContext(UUID(37L, 13L), NamedActionSource(Key("addon", "trigger")))
        for (context in listOf(ActionContext.SYSTEM, addon)) fixture().use { f ->
            acid(f)
            val target = f.block(2, 64, 2)
            reinforce(f, target)
            val completed = f.completedActions()
            val activation = AcidAction(context, f.position)
            val veto = object : ActionValidator<AcidAction> {
                override val actionClass = AcidAction::class.java
                override fun validate(action: AcidAction) = ActionValidator.Result.Rejected
            }
            f.actionFacade.registerValidator(veto)
            assertEquals(ActionResult.DENIED, f.actionFacade.execute(activation))
            assertTrue(completed.isEmpty())
            assertTrue(f.naturalBreaks.isEmpty())
            assertNotNull(f.citadels.findProtecting(f.position))
            assertNotNull(f.citadels.findProtecting(target.testPosition()))

            assertTrue(f.actions.unregisterValidator(veto))
            assertEquals(ActionResult.SUCCESS, f.actionFacade.execute(activation))
            assertEquals(3, completed.size)
            val source = assertIs<AcidDestroyAction>(completed[0])
            val destroyedTarget = assertIs<AcidDestroyAction>(completed[1])
            assertTrue(source.consumingSource)
            assertEquals(f.position, source.position)
            assertFalse(destroyedTarget.consumingSource)
            assertEquals(target.testPosition(), destroyedTarget.position)
            assertEquals(context, source.context)
            assertEquals(context, destroyedTarget.context)
            assertSame(activation, completed.last())
            assertTrue(f.block().isEmpty && target.isEmpty)
        }
    }

    @Test
    fun `player action enforces ownership and permissions and succeeds with no selected target`() = fixture().use { f ->
        acid(f)
        val completed = f.completedActions()
        assertEquals(ActionResult.DENIED, f.actionFacade.execute(AcidAction(f.context(f.stranger), f.position)))
        val action = AcidAction(f.context(f.installer), f.position)
        for (permission in listOf("fornaxcitadel.acid", "fornaxcitadel.damage")) {
            f.installer.permissions.remove(permission)
            assertEquals(ActionResult.DENIED, f.actionFacade.execute(action), permission)
            f.installer.permissions.add(permission)
        }
        assertTrue(completed.isEmpty())
        assertFalse(f.block().isEmpty)

        assertEquals(ActionResult.SUCCESS, f.actionFacade.execute(action))
        assertEquals(2, completed.size)
        assertTrue(assertIs<AcidDestroyAction>(completed.first()).consumingSource)
        assertSame(action, completed.last())
        assertTrue(f.block().isEmpty)
    }

    @Test
    fun `acid command reports owner and maturity failures then counts zero eligible targets`() = fixture().use { f ->
        f.material(Material.GOLD_BLOCK)
        f.place(PaperCitadelTypes.MATURING)
        val source = assertNotNull(f.citadels.findProtecting(f.position))
        val completed = f.completedActions()
        val command = f.command(acid = f.acidService)
        val messages = PaperMessages.defaults()
        fun activate(player: TestPlayer): Any? {
            player.target = f.block()
            command.onCommand(player.player, TestCommand, "citadel", arrayOf("acid"))
            return player.chatMessages.last()
        }

        assertEquals<Any?>(messages.render("commands.acid.not-owner"), activate(f.stranger))
        assertEquals<Any?>(messages.render("commands.acid.immature"), activate(f.installer))
        assertTrue(source.isCurrent)
        assertTrue(completed.isEmpty())
        source.editComponent(Maturing::class.java) { it.startedAtMillis = 0L }
        assertEquals<Any?>(messages.render("commands.acid.success", "count" to "0"), activate(f.installer))
        assertFalse(source.isCurrent)
        assertIs<AcidAction>(completed.last())
    }

    @Test
    fun `unbreakable and non-item materials retain physical blocks but lose acid and target reinforcements`() {
        for (material in listOf(Material.BEDROCK, Material.END_PORTAL)) for (retainSource in listOf(false, true)) {
            val settings = if (retainSource) AcidSettings(
                types = listOf(AcidType("retained", material, 1, 1.0))
            ) else AcidSettings()
            fixture(settings).use { f ->
                if (retainSource) {
                    reinforce(f, f.block())
                    f.material(material)
                } else acid(f)
                val source = assertNotNull(f.citadels.findProtecting(f.position))
                val target = f.block(2, 64, 2)
                reinforce(f, target)
                val reinforcement = assertNotNull(f.citadels.findProtecting(target.testPosition()))
                if (!retainSource) f.material(material, target.testPosition())

                assertEquals(AcidResult.Activated(1), activate(f))

                assertFalse(source.isCurrent || reinforcement.isCurrent)
                assertEquals(material, (if (retainSource) f.block() else target).type)
                assertTrue((if (retainSource) target else f.block()).isEmpty)
                assertEquals(1, f.vanillaBlockChanges)
                assertTrue(f.calledEvents.isEmpty())
                val items = f.droppedItems.map { it.second.type }
                assertFalse(Material.IRON_INGOT in items)
                assertEquals(1, items.count { it == if (retainSource) Material.COBBLESTONE else Material.GOLD_BLOCK })
                assertFalse(material in items)
                assertEquals(1, items.size)
            }
        }
    }

    @Test
    fun `non-container inventory holders keep their contents`() = fixture().use { f ->
        acid(f)
        val target = f.block(2, 64, 2)
        f.material(Material.CHISELED_BOOKSHELF, target.testPosition())
        reinforce(f, target)
        f.inventories[Triple(target.x, target.y, target.z)] = arrayOf(TestItemStack(Material.BOOK, 1))
        assertEquals(AcidResult.Activated(1), activate(f))
        assertEquals(listOf(Material.GOLD_BLOCK, Material.BOOK, Material.CHISELED_BOOKSHELF), f.droppedItems.map { it.second.type })
    }

    @Test
    fun `all independent door or bed reinforcements must permit one natural destruction`() {
        for (door in listOf(false, true)) for (reason in listOf("success", "higher tier", "veto")) fixture().use { f ->
            acid(f)
            val (first, second) = if (door) f.door(2, 64, 2) else f.bed(2, 64, 2)
            val firstCitadel = f.placeExact(first)
            val secondCitadel = f.placeExact(second,
                if (reason == "higher tier") PaperCitadelTypes.MATURING else PaperCitadelTypes.REINFORCED)
            if (reason == "veto") f.vetoAcid { it.position == second.testPosition() }

            assertEquals(AcidResult.Activated(if (reason == "success") 1 else 0), activate(f))

            assertEquals(reason != "success", firstCitadel.isCurrent)
            assertEquals(reason != "success", secondCitadel.isCurrent)
            assertEquals(reason == "success", first.isEmpty)
            assertEquals(reason == "success", second.isEmpty)
            assertTrue(f.block().isEmpty)
            assertEquals(if (reason == "success") listOf(f.block() to true, first to true)
                else listOf(f.block() to true), f.naturalBreaks)
            assertEquals(if (reason == "success") 1 else 0,
                f.droppedItems.count { it.second.type == if (door) Material.OAK_DOOR else Material.RED_BED })
            assertTrue(f.calledEvents.isEmpty())
        }
    }

    @Test
    fun `one compound provider call owns both parts and may refuse without preserving the reinforcement`() {
        for (door in listOf(false, true)) for (refuse in listOf(false, true)) fixture(settings = null).use { f ->
            acid(f)
            val (first, second) = if (door) f.door(2, 64, 2) else f.bed(2, 64, 2)
            reinforce(f, first)
            val target = assertNotNull(f.citadels.findProtecting(first.testPosition()))
            var attempts = 0
            val delegate = assertNotNull(f.dismantleMaterials.getTypeOrNull(first))
            val adapter = object : MaterialAdapter by delegate {
                override fun breakNaturallyBlock(block: Block, triggerEffect: Boolean): Boolean {
                    attempts++
                    assertSame(first, block)
                    assertTrue(triggerEffect)
                    return !refuse && delegate.breakNaturallyBlock(block, triggerEffect)
                }
                override fun createItemStack(amount: Int): org.bukkit.inventory.ItemStack = error("The compound provider owns loot")
            }
            val materials = object : MaterialRegistry by f.dismantleMaterials {
                override fun getTypeOrNull(block: Block): MaterialAdapter? =
                    if (block === first) adapter else f.dismantleMaterials.getTypeOrNull(block)
            }

            withMaterials(f, materials) { acid ->
                assertEquals(AcidResult.Activated(1), acid.activate(f.installer.player, f.block()))
            }

            assertEquals(1, attempts)
            assertFalse(target.isCurrent)
            assertEquals(!refuse, first.isEmpty)
            assertEquals(!refuse, second.isEmpty)
            assertEquals(if (refuse) listOf(f.block() to true) else listOf(f.block() to true, first to true), f.naturalBreaks)
            assertEquals(if (refuse) 0 else 1,
                f.droppedItems.count { it.second.type == if (door) Material.OAK_DOOR else Material.RED_BED })
            assertFalse(f.droppedItems.any { it.second.type == Material.IRON_INGOT })
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
    fun `missing and refusing container providers preserve inventories and double chest links`() {
        for (chest in listOf(false, true)) for (missing in listOf(false, true)) {
            fixture(settings = null).use { f ->
                acid(f)
                val (target, partner) = if (chest) f.doubleChest(2, 64, 2) else {
                    val block = f.block(2, 64, 2)
                    f.material(Material.CHISELED_BOOKSHELF, block.testPosition())
                    block to null
                }
                reinforce(f, target)
                val reinforcement = assertNotNull(f.citadels.findProtecting(target.testPosition()))
                val targetKey = Triple(target.x, target.y, target.z)
                val contents = TestItemStack(Material.EMERALD, 7)
                f.inventories[targetKey] = arrayOf(contents)
                val partnerKey = partner?.let { Triple(it.x, it.y, it.z) }
                if (partnerKey != null) f.inventories[partnerKey] = arrayOf(TestItemStack(Material.DIAMOND, 9))
                val initialData = target.blockData.asString
                val initialPartnerData = partner?.blockData?.asString
                var attempts = 0
                val refusing = object : MaterialAdapter by assertNotNull(f.dismantleMaterials.getTypeOrNull(target)) {
                    override fun breakNaturallyBlock(block: Block, triggerEffect: Boolean): Boolean {
                        assertSame(target, block)
                        attempts++
                        return false
                    }
                    override fun createItemStack(amount: Int): org.bukkit.inventory.ItemStack =
                        error("A retained container must not produce its block item")
                }
                val materials = object : MaterialRegistry by f.dismantleMaterials {
                    override fun getTypeOrNull(block: Block): MaterialAdapter? =
                        if (block === target) refusing.takeUnless { missing }
                        else f.dismantleMaterials.getTypeOrNull(block)
                }

                withMaterials(f, materials) { acid ->
                    assertEquals(AcidResult.Activated(1), acid.activate(f.installer.player, f.block()))
                }

                assertFalse(reinforcement.isCurrent)
                assertEquals(if (missing) 0 else 1, attempts)
                assertEquals(initialData, target.blockData.asString)
                assertEquals(initialPartnerData, partner?.blockData?.asString)
                assertSame(contents, f.inventories[targetKey]!!.single())
                assertEquals(7, contents.amount)
                if (partnerKey != null) assertEquals(9, f.inventories[partnerKey]!!.single()!!.amount)
                assertEquals(listOf(Material.GOLD_BLOCK), f.droppedItems.map { it.second.type })
                assertTrue(f.calledEvents.isEmpty())
                assertEquals(1, f.vanillaBlockChanges)
            }
        }
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
            f.beforeNaturalBreak = { if (it === target) {
                assertTrue(source.isEmpty)
                assertEquals(Chest.Type.SINGLE, (target.blockData as Chest).type)
            } }

            assertEquals(AcidResult.Activated(1), f.acidService!!.activate(f.installer.player, source))
            assertTrue(source.isEmpty && target.isEmpty)
            val items = f.droppedItems.map { it.second }
            assertEquals(7, items.single { it.type == Material.EMERALD }.amount)
            assertEquals(9, items.single { it.type == Material.DIAMOND }.amount)
            assertEquals(2, items.count { it.type == Material.CHEST })
            assertFalse(items.any { it.type == Material.IRON_INGOT })
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
        f.afterNaturalBreak = { if (it === first) {
            f.manager.unloadChunk(f.worldId, 1, 0).join()
            f.loadedWorldChunks.remove(1 to 0)
        } }

        assertEquals(AcidResult.Activated(1), f.acidService!!.activate(f.installer.player, source))
        assertTrue(source.isEmpty && first.isEmpty)
        assertFalse(last.isEmpty)
        f.loadChunk(1, 0)
        assertNotNull(f.citadels.findProtecting(last.testPosition()))
    }

    @Test
    fun `native destruction owns current container contents without service duplication`() {
        for (mutate in listOf(false, true)) fixture().use { f ->
            acid(f)
            val target = f.block(2, 64, 2)
            f.material(Material.CHISELED_BOOKSHELF, target.testPosition())
            reinforce(f, target)
            val key = Triple(target.x, target.y, target.z)
            f.inventories[key] = arrayOf(TestItemStack(Material.BOOK, 7))
            if (mutate) f.beforeNaturalBreak = { if (it === target) f.inventories[key] = arrayOf(TestItemStack(Material.BOOK, 8)) }

            assertEquals(AcidResult.Activated(1), activate(f))

            assertEquals(if (mutate) 8 else 7, f.droppedItems.single { it.second.type == Material.BOOK }.second.amount)
            assertEquals(1, f.droppedItems.count { it.second.type == Material.CHISELED_BOOKSHELF })
            assertEquals(3, f.droppedItems.size)
            assertTrue(f.inventories[key]!!.all { it == null })
            assertEquals(listOf(f.block() to true, target to true), f.naturalBreaks)
            assertTrue(f.calledEvents.isEmpty())
        }
    }

    @Test
    fun `multipart source is checked by core validation then consumed through one natural call`() {
        for (material in listOf(Material.OAK_DOOR, Material.RED_BED))
            fixture(AcidSettings(types = listOf(AcidType("multipart", material, 1, 1.0)))).use { f ->
                val (first, second) = if (material == Material.OAK_DOOR) f.door() else f.bed(1, 64, 2)
                reinforce(f, second)
                val source = assertNotNull(f.citadels.findProtecting(first.testPosition()))
                val anchor = if (first.testPosition() == source.position) first else second
                val clicked = if (anchor === first) second else first
                val target = anchor.getRelative(org.bukkit.block.BlockFace.NORTH)
                reinforce(f, target)
                var vetoSource = true
                f.vetoAcid { it.consumingSource && vetoSource }
                assertEquals(AcidResult.Failed(AcidFailure.DENIED), f.acidService!!.activate(f.installer.player, clicked))
                assertTrue(source.isCurrent)
                assertTrue(f.naturalBreaks.isEmpty())
                assertTrue(f.droppedItems.isEmpty())
                vetoSource = false

                assertEquals(AcidResult.Activated(1), f.acidService.activate(f.installer.player, clicked))

                assertTrue(first.isEmpty && second.isEmpty)
                assertEquals(listOf(anchor to true, target to true), f.naturalBreaks)
                assertEquals(1, f.droppedItems.count { it.second.type == material })
                assertTrue(f.calledEvents.isEmpty())
            }
    }

    @Test
    fun `a second independent reinforcement on a source part prevents activation`() {
        for (door in listOf(false, true)) {
            val material = if (door) Material.OAK_DOOR else Material.RED_BED
            fixture(AcidSettings(types = listOf(AcidType("multipart", material, 1, 1.0)))).use { f ->
                val (first, second) = if (door) f.door() else f.bed(1, 64, 2)
                val firstCitadel = f.placeExact(first)
                val secondCitadel = f.placeExact(second)

                assertEquals(AcidResult.Failed(AcidFailure.DENIED), f.acidService!!.activate(f.installer.player, first))

                assertTrue(firstCitadel.isCurrent && secondCitadel.isCurrent)
                assertFalse(first.isEmpty || second.isEmpty)
                assertTrue(f.naturalBreaks.isEmpty())
                assertTrue(f.droppedItems.isEmpty())
            }
        }
    }

    @Test
    fun `info completion permissions usage and activation share the root command`() =
        PaperTestFixture(permissions = true, acidSettings = AcidSettings()).use { f ->
            acid(f)
            reinforce(f, f.block(2, 64, 2))
            f.installer.target = f.block()
            f.installer.permissions.add("fornaxcitadel.info")
            val command = f.command(acid = f.acidService)
            val completed = f.completedActions()
            var activationAttempts = 0
            var veto = true
            f.actionFacade.registerValidator(object : ActionValidator<AcidAction> {
                override val actionClass = AcidAction::class.java
                override fun validate(action: AcidAction): ActionValidator.Result {
                    activationAttempts++
                    return if (veto) ActionValidator.Result.Rejected else ActionValidator.Result.Neutral
                }
            })
            assertEquals(listOf("acid"), command.onTabComplete(f.installer.player, TestCommand, "citadel", arrayOf("ac")))
            assertEquals(listOf("info"), command.onTabComplete(f.installer.player, TestCommand, "citadel", arrayOf("acid", "")))
            command.onCommand(f.installer.player, TestCommand, "citadel", arrayOf("acid", "info"))
            val messages = f.installer.chatMessages.filterIsInstance<Component>().map(PlainTextComponentSerializer.plainText()::serialize)
            assertEquals(8, messages.size)
            assertTrue(messages.any { "GOLD_BLOCK" in it })
            assertTrue(messages.any { "installer" in it })
            assertEquals(0, activationAttempts)
            assertTrue(completed.isEmpty())
            assertEquals(0, f.vanillaBlockChanges)
            assertTrue(f.calledEvents.isEmpty())
            command.onCommand(f.installer.player, TestCommand, "citadel", arrayOf("acid", "north"))
            assertEquals(0, f.vanillaBlockChanges)
            f.installer.permissions.remove("fornaxcitadel.acid")
            command.onCommand(f.installer.player, TestCommand, "citadel", arrayOf("acid"))
            assertEquals(0, f.vanillaBlockChanges)
            f.installer.permissions.add("fornaxcitadel.acid")
            command.onCommand(f.installer.player, TestCommand, "citadel", arrayOf("acid"))
            assertTrue(activationAttempts > 0)
            assertEquals<Any?>(PaperMessages.defaults().render("commands.acid.denied"), f.installer.chatMessages.last())
            assertTrue(completed.isEmpty())
            assertFalse(f.block().isEmpty)

            veto = false
            command.onCommand(f.installer.player, TestCommand, "citadel", arrayOf("acid"))
            val activation = assertIs<AcidAction>(completed.last())
            assertEquals(f.installer.id, activation.context.actorId)
            assertSame(f.installer.player, assertIs<PlayerSource>(activation.context.source).player)
            assertEquals(Key("paper", "acid"), activation.context.source.key)
            assertEquals<Any?>(PaperMessages.defaults().render("commands.acid.success", "count" to "1"),
                f.installer.chatMessages.last())
            assertTrue(f.block().isEmpty)
        }

    private fun PaperTestFixture.completedActions(): List<Action> {
        val completed = mutableListOf<Action>()
        actionFacade.registerHandler(object : ActionHandler<Action> {
            override val actionClass = Action::class.java
            override fun handle(action: Action) { completed.add(action) }
        })
        return completed
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

    @Test
    fun `acid ray preserves absent bounds and rejects either unavailable chunk before looking up blocks`() =
        PaperTestFixture(load = false, acidSettings = AcidSettings()).use { f ->
            val service = assertNotNull(f.acidService)
            val initialLookups = f.blockLookups
            assertFailsWith<ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException> {
                service.findTarget(f.installer.player)
            }
            assertEquals(initialLookups, f.blockLookups)

            f.loadChunk(0, 0)
            f.worldChunkLoaded = false
            assertFailsWith<ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException> {
                service.findTarget(f.installer.player)
            }
            assertEquals(initialLookups, f.blockLookups)
            f.worldChunkLoaded = true

            for (location in listOf(
                org.bukkit.Location(f.world, 0.5, -65.5, 0.5),
                org.bukkit.Location(f.world, 0.5, 320.5, 0.5),
                org.bukkit.Location(f.world, (Short.MAX_VALUE.toInt() + 1) * 16.0, 64.5, 0.5)
            )) {
                f.installer.location = location
                assertNull(service.findTarget(f.installer.player))
            }
            assertEquals(initialLookups, f.blockLookups)
        }

    private fun acid(f: PaperTestFixture, block: Block = f.block()) {
        f.material(Material.GOLD_BLOCK, block.testPosition())
        reinforce(f, block)
    }
    private fun reinforce(f: PaperTestFixture, block: Block) {
        val type = PaperCitadelTypes.REINFORCED
        assertEquals(ActionResult.SUCCESS, f.actions.execute(PlaceAction(f.context(f.installer), block.testPosition(), type)))
    }
    private fun PaperTestFixture.vetoAcid(predicate: (AcidDestroyAction) -> Boolean) {
        actions.registerValidator(object : ActionValidator<AcidDestroyAction> {
            override val actionClass = AcidDestroyAction::class.java
            override fun validate(action: AcidDestroyAction) = if (predicate(action))
                ActionValidator.Result.Rejected else ActionValidator.Result.Neutral
        })
    }
    private fun withMaterials(f: PaperTestFixture, materials: MaterialRegistry, body: (AcidService) -> Unit) {
        val service = AcidService(
            f.plugin, f.actionFacade, f.citadels, f.coreGameplay.maturationFacade,
            f.citadelTypes, materials, f.materialPlacement, f.breakingService, AcidRegistry(AcidSettings())
        )
        service.enable()
        try { body(service) } finally { service.disable() }
    }
    private fun activate(f: PaperTestFixture) = f.acidService!!.activate(f.installer.player, f.block())
    private fun fixture(settings: AcidSettings? = AcidSettings()) = PaperTestFixture(permissions = true,
        placementSettings = PlacementSettings(BlockListType.BLACKLIST, emptySet()), acidSettings = settings)
    private object TestCommand : Command("citadel") {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>) = error("unused")
    }
}
