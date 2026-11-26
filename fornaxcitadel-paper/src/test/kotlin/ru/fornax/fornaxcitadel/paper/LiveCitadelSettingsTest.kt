package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthDefinition
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.modules.health.DamageModifier
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.paper.config.PaperMessages
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidFailure
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidResult
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidSettings
import ru.fornax.fornaxcitadel.paper.modules.dismantle.DismantleSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import kotlin.test.*

class LiveCitadelSettingsTest {
    private val original = typesWithItem(Key("minecraft", "iron_ingot"))

    @Test
    fun `restart adds effective health without writes or repair cost until first authorized damage`() {
        restored(savedWithoutHealth(), changed(100.0)).use { f ->
            val target = assertNotNull(f.citadels.findProtecting(f.position))
            val owner = target.getComponent(CitadelOwner::class.java)?.playerId
            val started = target.getComponent(Maturing::class.java)?.startedAtMillis
            val saves = f.backend.saves
            assertNull(target.getComponent(Health::class.java))
            assertEquals(100.0, f.coreGameplay.health.currentHealth(target))
            assertTrue(info(f).any { it.contains("100.0 / 100.0") })

            f.installer.held = TestItemStack(Material.DIAMOND, 2)
            assertEquals(ActionResult.DENIED, f.actions.execute(RepairAction(f.context(f.installer), f.position)))
            val event = PlayerInteractEvent(f.installer.player, Action.RIGHT_CLICK_BLOCK, f.installer.held,
                f.block(), BlockFace.UP, EquipmentSlot.HAND)
            val blockUse = event.useInteractedBlock()
            val itemUse = event.useItemInHand()
            dispatch(f.placementListener, event, f.plugin)
            assertEquals(blockUse, event.useInteractedBlock())
            assertEquals(itemUse, event.useItemInHand())
            assertEquals(2, f.installer.held.amount)
            assertNull(target.getComponent(Health::class.java))
            f.manager.saveAll().join()
            assertEquals(saves, f.backend.saves)

            assertEquals(ActionResult.SUCCESS, damage(f, 10.0))
            assertEquals(90.0, target.getComponent(Health::class.java)?.health)
            assertEquals(owner, target.getComponent(CitadelOwner::class.java)?.playerId)
            assertEquals(started, target.getComponent(Maturing::class.java)?.startedAtMillis)
            f.manager.unloadChunk(f.worldId, 0, 0).join()
            f.loadChunk(0, 0)
            val reloaded = assertNotNull(f.citadels.findProtecting(f.position))
            assertEquals(90.0, reloaded.getComponent(Health::class.java)?.health)
            f.coreGameplay.health.registerDefinition(PaperCitadelTypes.REINFORCED, HealthDefinition(200.0))
            assertEquals(90.0, f.coreGameplay.health.currentHealth(reloaded))
            assertTrue(info(f).any { it.contains("90.0 / 200.0") })
        }
    }

    @Test
    fun `denied ineffective and failed first hits leave restored reinforcement without health or new saves`() {
        restored(savedWithoutHealth(), changed(100.0)).use { f ->
            val target = assertNotNull(f.citadels.findProtecting(f.position))
            val saves = f.backend.saves
            f.stranger.permissions.remove("fornaxcitadel.damage")
            assertEquals(ActionResult.DENIED, damage(f, 10.0))
            f.stranger.permissions.add("fornaxcitadel.damage")
            val reject = object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction) = ActionValidator.Result.Rejected
            }
            f.actions.registerValidator(reject)
            assertEquals(ActionResult.DENIED, damage(f, 10.0))
            f.actions.unregisterValidator(reject)
            assertEquals(ActionResult.DENIED, damage(f, 0.0))
            val zero = DamageModifier { _, _, _ -> 0.0 }
            f.coreGameplay.health.registerModifier(zero)
            assertEquals(ActionResult.DENIED, damage(f, 10.0))
            f.coreGameplay.health.unregisterModifier(zero)
            f.coreGameplay.health.registerModifier { _, _, _ -> error("Calculation failed") }
            assertFailsWith<IllegalStateException> { damage(f, 10.0) }
            assertNull(target.getComponent(Health::class.java))
            assertEquals(100.0, f.coreGameplay.health.currentHealth(target))
            f.manager.saveAll().join()
            assertEquals(saves, f.backend.saves)
        }
    }

    @Test
    fun `restored reinforcement reads new item level and max without rewriting HP`() {
        restored(saved(), changed(50.0)).use { f ->
            val target = assertNotNull(f.citadels.findProtecting(f.position))
            assertEquals(80.0, f.health())
            assertEquals(50.0, f.coreGameplay.health.maxHealth(target))
            assertEquals(7, f.citadelTypes.level(target))
            assertEquals(Key("minecraft", "diamond"), f.citadelTypes.itemKey(target))
            val saves = f.backend.saves
            assertEquals(80.0, f.coreGameplay.health.currentHealth(target))
            assertTrue(info(f).any { it.contains("50.0 / 50.0") })
            assertEquals(80.0, f.health())
            assertEquals(80.0, f.coreGameplay.health.currentHealth(target))
            f.manager.saveAll().join()
            assertEquals(saves, f.backend.saves)

            val reject = object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction) = ActionValidator.Result.Rejected
            }
            f.actions.registerValidator(reject)
            assertEquals(ActionResult.DENIED, damage(f, 1.0))
            assertEquals(80.0, f.health())
            f.actions.unregisterValidator(reject)
            assertEquals(ActionResult.DENIED, damage(f, 0.0))
            assertEquals(80.0, f.health())
            assertEquals(ActionResult.SUCCESS, damage(f, 1.0))
            assertEquals(49.0, f.health())
            assertTrue(info(f).any { it.contains("49.0 / 50.0") })
        }
    }

    @Test
    fun `increased maximum does not heal and repair requires the new configured item`() {
        restored(saved(), changed(200.0)).use { f ->
            assertEquals(80.0, f.health())
            val repair = RepairAction(f.context(f.installer), f.position)
            f.installer.held = TestItemStack(Material.IRON_INGOT, 2)
            assertEquals(ActionResult.DENIED, f.actions.execute(repair))
            assertEquals(80.0, f.health())
            assertEquals(2, f.installer.held.amount)
            f.installer.held = TestItemStack(Material.DIAMOND, 2)
            assertEquals(ActionResult.SUCCESS, f.actions.execute(repair))
            assertEquals(200.0, f.health())
            assertEquals(1, f.installer.held.amount)
        }
    }

    @Test
    fun `dismantle treats overmaximum HP as full and refunds the new material`() {
        restored(saved(), changed(50.0)).use { f ->
            assertEquals(ActionResult.SUCCESS, f.actions.execute(DismantleAction(f.context(f.installer), f.position)))
            assertEquals(Material.DIAMOND, f.droppedItems.single().second.type)
            assertEquals(1, f.droppedItems.single().second.amount)
        }
    }

    @Test
    fun `acid never refunds configured reinforcement items after restart`() {
        restored(saved(neighbor = true), changed(50.0), acid = true).use { f ->
            assertEquals(AcidResult.Activated(1), f.acidService!!.activate(f.installer.player, f.block()))
            assertEquals(listOf(Material.GOLD_BLOCK, Material.COBBLESTONE), f.droppedItems.map { it.second.type })
            assertTrue(f.droppedItems.all { it.second.amount == 1 })
        }
    }

    @Test
    fun `removed type retains usable HP shows no invented maximum and dismantles without refund`() {
        restored(saved(), CitadelTypesSettings(emptyMap())).use { f ->
            val target = assertNotNull(f.citadels.findProtecting(f.position))
            assertNull(f.coreGameplay.health.maxHealth(target))
            assertNull(f.citadelTypes.itemKey(target))
            assertNull(f.citadelTypes.level(target))
            assertEquals(80.0, f.health())
            assertTrue(info(f).any { it.contains("80.0") && !it.contains("/") })
            assertEquals(ActionResult.SUCCESS, damage(f, 1.0))
            assertEquals(79.0, f.health())
            assertEquals(ActionResult.DENIED, f.actions.execute(RepairAction(f.context(f.installer), f.position)))
            assertEquals(ActionResult.SUCCESS, f.actions.execute(DismantleAction(f.context(f.installer), f.position)))
            assertTrue(f.droppedItems.isEmpty())
        }
    }

    @Test
    fun `absent maximum prevents repair and uses configured damaged refund chance`() {
        restored(saved(), changed(50.0)).use { f ->
            f.coreGameplay.health.unregisterDefinition(PaperCitadelTypes.REINFORCED)
            assertEquals(ActionResult.SUCCESS, damage(f, 1.0))
            assertEquals(79.0, f.health())
            f.installer.held = TestItemStack(Material.DIAMOND, 2)
            assertEquals(ActionResult.DENIED, f.actions.execute(RepairAction(f.context(f.installer), f.position)))
            assertEquals(2, f.installer.held.amount)
            assertEquals(ActionResult.SUCCESS, f.actions.execute(DismantleAction(f.context(f.installer), f.position)))
            assertTrue(f.droppedItems.isEmpty())
        }
    }

    @Test
    fun `registered level-less types respect acid tier comparisons and invulnerability`() {
        val ordinary = Key("addon", "ordinary")
        val protected = Key("addon", "protected")
        for (sourceType in listOf(ordinary, PaperCitadelTypes.REINFORCED)) {
            PaperTestFixture(permissions = true, acidSettings = AcidSettings()).use { f ->
                val health = f.coreGameplay.healthFacade
                for ((key, profile) in mapOf(ordinary to HealthDefinition(10.0), protected to HealthDefinition(null, true))) {
                    health.registerDefinition(key, profile)
                    f.coreGameplay.definitions.register(CitadelDefinition(key, listOf(HealthInitializer(health))))
                }
                f.material(Material.NETHERITE_BLOCK)
                f.place(sourceType)
                fun placeAt(block: Block, key: Key) = f.actions.execute(PlaceAction(f.context(f.installer), block.testPosition(), key))
                val ordinaryTarget = f.block().getRelative(BlockFace.UP)
                val protectedTarget = f.block().getRelative(BlockFace.DOWN)
                val leveledTarget = f.block().getRelative(BlockFace.NORTH)
                assertEquals(ActionResult.SUCCESS, placeAt(ordinaryTarget, ordinary))
                assertEquals(ActionResult.SUCCESS, placeAt(leveledTarget, PaperCitadelTypes.REINFORCED))
                assertEquals(ActionResult.DENIED, placeAt(protectedTarget, protected))
                f.installer.permissions.add("fornaxcitadel.admin")
                assertEquals(ActionResult.SUCCESS, placeAt(protectedTarget, protected))
                f.installer.permissions.remove("fornaxcitadel.admin")
                assertContains(info(f, protectedTarget), PaperMessages.defaults().plainText("commands.info.invulnerable"))

                val acid = assertNotNull(f.acidService)
                val inspected = assertIs<AcidResult.Information>(acid.inspect(f.installer.player, f.block()))
                val sourceHasLevel = sourceType == PaperCitadelTypes.REINFORCED
                val expectedTargets = if (sourceHasLevel) 2 else 1
                assertEquals(if (sourceHasLevel) 2 else null, inspected.tier)
                assertEquals(f.installer.id, inspected.owner)
                assertEquals(expectedTargets, inspected.selectedTargets)
                assertEquals(AcidResult.Failed(AcidFailure.NOT_OWNER), acid.activate(f.stranger.player, f.block()))
                assertEquals(AcidResult.Activated(expectedTargets), acid.activate(f.installer.player, f.block()))
                assertNull(f.citadels.findProtecting(ordinaryTarget.testPosition()))
                assertNotNull(f.citadels.findProtecting(protectedTarget.testPosition()))
                assertEquals(sourceHasLevel, f.citadels.findProtecting(leveledTarget.testPosition()) == null)
            }
        }
    }

    @Test
    fun `unknown acid level only reaches unknown targets while any level reaches unknown targets`() {
        for (sourceKnown in listOf(false, true)) for (targetKnown in listOf(false, true)) {
            PaperTestFixture(permissions = true, acidSettings = AcidSettings()).use { f ->
                f.material(Material.GOLD_BLOCK)
                f.place()
                val source = assertNotNull(f.citadels.findProtecting(f.position))
                val adjacent = f.block().getRelative(BlockFace.UP)
                assertEquals(ActionResult.SUCCESS, f.actions.execute(PlaceAction(
                    f.context(f.installer), adjacent.testPosition(), PaperCitadelTypes.MATURING)))
                val target = assertNotNull(f.citadels.findProtecting(adjacent.testPosition()))
                if (!sourceKnown) source.editComponent(CitadelKind::class.java) { it.definitionKey = Key("test", "missing_source") }
                if (!targetKnown) target.editComponent(CitadelKind::class.java) { it.definitionKey = Key("test", "missing_target") }
                val info = assertIs<AcidResult.Information>(f.acidService!!.inspect(f.installer.player, f.block()))
                assertEquals(if (sourceKnown) 2 else null, info.tier)
                assertEquals(AcidResult.Activated(if (targetKnown) 0 else 1), f.acidService.activate(f.installer.player, f.block()))
                assertEquals(targetKnown, target.isCurrent)
            }
        }
    }

    private fun changed(maximum: Double): CitadelTypesSettings = CitadelTypesSettings(original.types - "reinforced_heavy" +
        ("reinforced" to original.types.getValue("reinforced").copy(
            item = Key("minecraft", "diamond"), lvl = 7, health = HealthDefinition(maximum))))

    private fun saved(neighbor: Boolean = false): ChunkCordSnapshot =
        PaperTestFixture(permissions = true, citadelSettings = original).use { f ->
            f.place()
            assertNotNull(f.citadels.findProtecting(f.position)).editComponent(Health::class.java) { it.health = 80.0 }
            if (neighbor) assertEquals(ActionResult.SUCCESS, f.actions.execute(PlaceAction(
                f.context(f.installer), f.position.copy(y = f.position.y + 1), PaperCitadelTypes.REINFORCED)))
            f.manager.unloadChunk(f.worldId, 0, 0).join()
            assertNotNull(f.backend.loadChunk(WXZCord(f.worldId, 0, 0)).join())
        }

    private fun savedWithoutHealth(): ChunkCordSnapshot {
        val prior = CitadelTypesSettings(original.types + ("reinforced" to original.types.getValue("reinforced")
            .copy(health = HealthDefinition(null, invulnerable = true), maturing = null)))
        return PaperTestFixture(permissions = true, citadelSettings = prior).use { f ->
            f.installer.permissions.add("fornaxcitadel.admin")
            f.place()
            assertNull(f.citadels.findProtecting(f.position)?.getComponent(Health::class.java))
            f.manager.unloadChunk(f.worldId, 0, 0).join()
            assertNotNull(f.backend.loadChunk(WXZCord(f.worldId, 0, 0)).join())
        }
    }

    private fun restored(snapshot: ChunkCordSnapshot, settings: CitadelTypesSettings, acid: Boolean = false) =
        PaperTestFixture(load = false, permissions = true, citadelSettings = settings,
            dismantleSettings = DismantleSettings(0.0),
            acidSettings = if (acid) AcidSettings() else null).apply {
            backend.saveChunk(snapshot).join()
            loadChunk(0, 0)
            if (acid) material(Material.GOLD_BLOCK)
        }

    private fun damage(f: PaperTestFixture, amount: Double) =
        f.actions.execute(DamageAction(f.context(f.stranger), f.position, amount))

    private fun info(f: PaperTestFixture, block: Block = f.block()): List<String> {
        f.installer.target = block
        f.installer.permissions.add("fornaxcitadel.info")
        val commands = f.command()
        commands.onCommand(f.installer.player, TestCommand, "citadel", arrayOf("info"))
        return f.installer.chatMessages.filterIsInstance<Component>().map(PlainTextComponentSerializer.plainText()::serialize)
    }

    private object TestCommand : Command("citadel") {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>) = true
    }
}
