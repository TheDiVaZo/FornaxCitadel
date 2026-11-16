package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthDefinition
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.material.MaterialAdapter
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidResult
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidSettings
import ru.fornax.fornaxcitadel.paper.modules.dismantle.DismantleSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.BlockListType
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.HealthSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.placement.PlacementSettings
import java.io.DataInput
import java.io.DataOutput
import kotlin.test.*

class PaperUpgradeServiceTest {
    @Test
    fun `upgrade preserves identity owner and extra components and completes before notifying handlers`() {
        for (mode in listOf(GameMode.SURVIVAL, GameMode.CREATIVE)) for (amount in listOf(1, 3)) {
            fixture(dismantleSettings = DismantleSettings(0.0)).use { f ->
                f.place()
                val original = f.citadel()
                val owner = original.getComponent(CitadelOwner::class.java)
                f.manager.registerComponent(Key("test", "marker"), Marker::class.java)
                val marker = Marker().apply { value = 42 }
                f.manager.putComponent(Marker::class.java, f.worldId, 1, 64, 2, marker)
                original.editComponent(Maturing::class.java) { it.startedAtMillis = 1 }
                f.installer.gameMode = mode
                f.installer.held = f.reinforcementStack(HEAVY, amount)
                var completed = 0
                f.module.actions.handlerCoordinator.registerHandler(object : ActionHandler<ActionData> {
                    override val actionClass = ActionData::class.java
                    override fun handle(action: ActionData) {
                        assertIs<UpgradeAction>(action)
                        assertTrue(original.isCurrent)
                        assertEquals(original.runtimeId, f.citadel().runtimeId)
                        assertEquals(HEAVY, f.citadel().getComponent(CitadelKind::class.java)?.definitionKey)
                        assertEquals(30.0, f.health())
                        assertEquals(amount - if (mode == GameMode.CREATIVE) 0 else 1, f.installer.held.amount)
                        assertEquals(Material.IRON_INGOT, f.droppedItems.single().second.type)
                        completed++
                    }
                })

                val earliest = System.currentTimeMillis()
                val event = f.click()
                dispatch(f.placementListener, event, f.plugin)

                assertEquals(Event.Result.DENY, event.useItemInHand())
                assertEquals(Event.Result.DENY, event.useInteractedBlock())
                assertEquals(1, completed)
                assertSame(owner, original.getComponent(CitadelOwner::class.java))
                assertSame(marker, original.getComponent(Marker::class.java))
                assertTrue(original.getComponent(Maturing::class.java)!!.startedAtMillis >= earliest)
                assertEquals(1, f.droppedItems.single().second.amount)
                assertEquals(0, f.vanillaBlockChanges)
                assertTrue(f.playerBreaks.isEmpty())
                assertEquals<Any?>(PaperMessages.defaults().render("upgrade.success"), f.installer.actionBarMessages.single())
                assertEquals(ActionResult.DENIED, f.module.actions.execute(f.action()))
                assertEquals(1, completed)
                assertEquals(1, f.droppedItems.size)
            }
        }
    }

    @Test
    fun `known characteristics cannot decrease and at least one must grow using maximum not damaged HP`() {
        for ((level, maximum, allowed) in listOf(
            Triple(3, 20.0, true), Triple(2, 30.0, true), Triple(3, 30.0, true),
            Triple(3, 10.0, false), Triple(1, 30.0, false), Triple(2, 20.0, false)
        )) {
            val types = CitadelTypesSettings.DEFAULT_TYPES.toMutableMap()
            types[HEAVY.key] = types.getValue(HEAVY.key).copy(lvl = level, health = HealthSettings(maximum))
            PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings(types)).use { f ->
                f.place()
                f.citadel().editComponent(Health::class.java) { it.health = 1.0 }
                f.installer.held = f.reinforcementStack(HEAVY)
                assertEquals(if (allowed) ActionResult.SUCCESS else ActionResult.DENIED,
                    f.module.actions.execute(f.action()), "$level / $maximum")
                assertEquals(if (allowed) maximum else 1.0, f.health())
                assertEquals(if (allowed) 1 else 2, f.installer.held.amount)
                if (!allowed) assertTrue(f.droppedItems.isEmpty())
            }
        }
    }

    @Test
    fun `unknown kind unknown definition and absent maximum fall back to current HP`() {
        for (missing in listOf("kind", "definition", "maximum")) fixture().use { f ->
            f.place()
            val original = f.citadel()
            when (missing) {
                "kind" -> f.manager.removeComponent(CitadelKind::class.java, f.worldId, 1, 64, 2)
                "definition" -> original.editComponent(CitadelKind::class.java) { it.definitionKey = Key("test", "missing") }
                "maximum" -> f.module.health.unregisterDefinition(PaperCitadelTypes.REINFORCED)
            }
            f.installer.held = f.reinforcementStack(HEAVY)
            original.editComponent(Health::class.java) { it.health = 80.0 }
            assertEquals(ActionResult.DENIED, f.module.actions.execute(f.action()), missing)
            assertEquals(80.0, f.health())
            assertEquals(2, f.installer.held.amount)
            if (missing != "maximum") {
                original.editComponent(Health::class.java) { it.health = 30.0 }
                assertEquals(ActionResult.DENIED, f.module.actions.execute(f.action()), "Unknown level must not count as a gain")
            }
            original.editComponent(Health::class.java) { it.health = 10.0 }
            dispatch(f.placementListener, f.click(), f.plugin)
            assertEquals(HEAVY, original.getComponent(CitadelKind::class.java)?.definitionKey)
            assertEquals(30.0, f.health())
            assertEquals(1, f.installer.held.amount)
            if (missing != "maximum") assertTrue(f.droppedItems.isEmpty())
            assertEquals(f.installer.id, original.getComponent(CitadelOwner::class.java)?.playerId)
        }
    }

    @Test
    fun `no comparison data invalid HP and unknown destination never upgrade`() {
        for (reason in listOf("no data", "nan", "negative", "destination")) fixture().use { f ->
            f.place()
            f.installer.held = f.reinforcementStack(HEAVY)
            when (reason) {
                "no data" -> {
                    f.manager.removeComponent(CitadelKind::class.java, f.worldId, 1, 64, 2)
                    f.manager.removeComponent(Health::class.java, f.worldId, 1, 64, 2)
                }
                "nan" -> f.citadel().editComponent(Health::class.java) { it.health = Double.NaN }
                "negative" -> f.citadel().editComponent(Health::class.java) { it.health = -1.0 }
            }
            assertEquals(ActionResult.DENIED, f.module.actions.execute(f.action(
                if (reason == "destination") Key("test", "missing") else HEAVY)), reason)
            assertEquals(2, f.installer.held.amount)
            assertTrue(f.droppedItems.isEmpty())
        }
    }

    @Test
    fun `refund guarantees use the old known maximum and damaged refunds use dismantling probability`() {
        for ((health, chance, sample, drop) in listOf(
            RefundCase(20.0, 0.0, 0.9, true), RefundCase(80.0, 0.0, 0.9, true),
            RefundCase(10.0, 0.0, 0.0, false), RefundCase(10.0, 1.0, 0.9, true),
            RefundCase(10.0, 0.5, 0.499, true), RefundCase(10.0, 0.5, 0.5, false)
        )) {
            var rolls = 0
            fixture(DismantleSettings(chance)) { rolls++; sample }.use { f ->
                f.place()
                f.citadel().editComponent(Health::class.java) { it.health = health }
                f.installer.held = f.reinforcementStack(HEAVY)
                assertEquals(ActionResult.SUCCESS, f.module.actions.execute(f.action()))
                assertEquals(if (drop) 1 else 0, f.droppedItems.size)
                assertEquals(if (health < 20 && chance > 0 && chance < 1) 1 else 0, rolls)
            }
        }
        for (missing in listOf("maximum", "health", "item")) {
            fixture(DismantleSettings(0.0)) { error("Absent or guaranteed refund must not roll") }.use { f ->
                f.place()
                when (missing) {
                    "maximum" -> f.module.health.unregisterDefinition(PaperCitadelTypes.REINFORCED)
                    "health" -> f.manager.removeComponent(Health::class.java, f.worldId, 1, 64, 2)
                    "item" -> f.manager.removeComponent(CitadelKind::class.java, f.worldId, 1, 64, 2)
                }
                f.installer.held = f.reinforcementStack(HEAVY)
                assertEquals(ActionResult.SUCCESS, f.module.actions.execute(f.action()), missing)
                assertEquals(if (missing == "health") 1 else 0, f.droppedItems.size, missing)
                if (missing == "health") {
                    assertEquals(Material.IRON_INGOT, f.droppedItems.single().second.type)
                    assertEquals(1, f.droppedItems.single().second.amount)
                }
            }
        }
    }

    @Test
    fun `ownership and both permissions are required and validators may explicitly grant or veto`() {
        fixture().use { f ->
            f.place()
            f.installer.held = f.reinforcementStack(HEAVY)
            for (permission in listOf("fornaxcitadel.place", "fornaxcitadel.repair")) {
                f.installer.permissions.remove(permission)
                assertEquals(ActionResult.DENIED, f.module.actions.execute(f.action()))
                f.installer.permissions.add(permission)
            }
            f.stranger.held = f.reinforcementStack(HEAVY)
            f.stranger.permissions.add("fornaxcitadel.admin")
            val request = UpgradeAction(f.context(f.stranger), f.position, HEAVY)
            assertEquals(ActionResult.DENIED, f.module.actions.execute(request))
            f.validate { ActionValidator.Result.Success }
            val veto = f.validate { ActionValidator.Result.Rejected }
            assertEquals(ActionResult.DENIED, f.module.actions.execute(request))
            assertEquals(20.0, f.health())
            assertEquals(2, f.stranger.held.amount)
            assertTrue(f.droppedItems.isEmpty())
            f.module.actions.validatorCoordinator.unregisterValidator(veto)
            assertEquals(ActionResult.SUCCESS, f.module.actions.execute(request))
            assertEquals(f.installer.id, f.citadel().getComponent(CitadelOwner::class.java)?.playerId)
        }
    }

    @Test
    fun `invulnerable upgrade needs admin and removes health but cannot downgrade to damageable`() {
        val types = CitadelTypesSettings.DEFAULT_TYPES.toMutableMap()
        types[HEAVY.key] = types.getValue(HEAVY.key).copy(lvl = 1000, health = HealthSettings(1000.0))
        PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings(types)).use { f ->
            f.place()
            f.installer.held = f.reinforcementStack(PaperCitadelTypes.INVULNERABLE)
            assertEquals(ActionResult.DENIED, f.module.actions.execute(f.action(PaperCitadelTypes.INVULNERABLE)))
            assertEquals(20.0, f.health())
            f.installer.permissions.add("fornaxcitadel.admin")
            assertEquals(ActionResult.SUCCESS, f.module.actions.execute(f.action(PaperCitadelTypes.INVULNERABLE)))
            assertNull(f.citadel().getComponent(Health::class.java))
            assertEquals(PaperCitadelTypes.INVULNERABLE, f.citadel().getComponent(CitadelKind::class.java)?.definitionKey)
            assertEquals(1, f.droppedItems.size)
            f.installer.held = f.reinforcementStack(HEAVY)
            assertEquals(ActionResult.DENIED, f.module.actions.execute(f.action()))
            assertEquals(2, f.installer.held.amount)
        }
        fixture().use { f ->
            f.place()
            f.manager.removeComponent(CitadelKind::class.java, f.worldId, 1, 64, 2)
            f.installer.permissions.add("fornaxcitadel.admin")
            f.installer.held = f.reinforcementStack(PaperCitadelTypes.INVULNERABLE)
            assertEquals(ActionResult.DENIED, f.module.actions.execute(f.action(PaperCitadelTypes.INVULNERABLE)))
            assertEquals(20.0, f.health())
        }
    }

    @Test
    fun `queued actions recheck player world item chunk thread and placement restrictions`() {
        for (reason in listOf("offline", "spectator", "item", "chunk", "storage", "thread", "air", "piston", "list", "permission")) {
            fixture().use { f ->
                f.place()
                val original = f.citadel()
                f.installer.held = f.reinforcementStack(HEAVY)
                f.validate { ActionValidator.Result.Success }
                f.module.actions.post(f.action())
                when (reason) {
                    "offline" -> f.installer.online = false
                    "spectator" -> f.installer.gameMode = GameMode.SPECTATOR
                    "item" -> f.installer.held = TestItemStack(Material.DIRT, 2)
                    "chunk" -> f.worldChunkLoaded = false
                    "storage" -> f.manager.unloadChunk(f.worldId, 0, 0).join()
                    "thread" -> f.mainThread = false
                    "air" -> f.material(Material.AIR)
                    "piston" -> f.material(Material.PISTON)
                    "list" -> f.material(Material.DIRT)
                    "permission" -> f.installer.permissions.remove("fornaxcitadel.repair")
                }
                var completed = 0
                f.module.actions.handlerCoordinator.registerHandler(object : ActionHandler<UpgradeAction> {
                    override val actionClass = UpgradeAction::class.java
                    override fun handle(action: UpgradeAction) { completed++ }
                })
                assertEquals(1, f.module.actions.drainPosted())
                assertEquals(0, completed, reason)
                if (original.isCurrent) assertEquals(20.0, original.getComponent(Health::class.java)?.health)
                assertEquals(2, f.installer.held.amount)
                assertTrue(f.droppedItems.isEmpty())
            }
        }
    }

    @Test
    fun `maturation restarts using the new profile and changed components survive chunk reload`() {
        fixture().use { f ->
            f.place()
            val original = f.citadel()
            original.editComponent(Maturing::class.java) { it.startedAtMillis = 1L }
            f.installer.held = f.reinforcementStack(PaperCitadelTypes.MATURING)
            assertEquals(ActionResult.SUCCESS, f.module.actions.execute(f.action(PaperCitadelTypes.MATURING)))
            val started = original.getComponent(Maturing::class.java)!!.startedAtMillis
            assertTrue(started > 1L)
            assertFalse(f.module.health.maturation.isMature(original))
            f.manager.unloadChunk(f.worldId, 0, 0).join()
            f.loadChunk(0, 0)
            val restored = f.citadel()
            assertEquals(PaperCitadelTypes.MATURING, restored.getComponent(CitadelKind::class.java)?.definitionKey)
            assertEquals(40.0, f.health())
            assertEquals(started, restored.getComponent(Maturing::class.java)?.startedAtMillis)
            assertEquals(f.installer.id, restored.getComponent(CitadelOwner::class.java)?.playerId)
            assertFalse(f.module.health.maturation.isMature(restored))
        }
    }

    @Test
    fun `upgraded acid retains its multiplier and uses the new reinforcement tier`() {
        PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings(), acidSettings = AcidSettings()).use { f ->
            f.material(Material.GOLD_BLOCK)
            f.place()
            val acid = assertNotNull(f.acidService)
            assertTrue(assertIs<AcidResult.Information>(acid.inspect(f.installer.player, f.block())).mature)
            f.installer.held = f.reinforcementStack(PaperCitadelTypes.MATURING)
            assertEquals(ActionResult.SUCCESS, f.module.actions.execute(f.action(PaperCitadelTypes.MATURING)))
            val target = f.citadel()
            // The new base duration is 600 seconds; gold's x2 multiplier makes it 1200 seconds.
            target.editComponent(Maturing::class.java) { it.startedAtMillis = System.currentTimeMillis() - 700_000L }
            val info = assertIs<AcidResult.Information>(acid.inspect(f.installer.player, f.block()))
            assertEquals(4, info.tier)
            assertFalse(info.mature)
            assertTrue(f.module.health.maturation.progress(target) in 0.58..0.60)
            target.editComponent(Maturing::class.java) { it.startedAtMillis = System.currentTimeMillis() - 1_300_000L }
            assertTrue(assertIs<AcidResult.Information>(acid.inspect(f.installer.player, f.block())).mature)
            assertEquals(Material.GOLD_BLOCK, f.block().type)
        }
    }

    @Test
    fun `multipart interaction upgrades only its existing responsible entity when sneaking`() {
        for (kind in listOf("door", "bed", "chest")) {
            PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings(),
                placementSettings = PlacementSettings(BlockListType.BLACKLIST, emptySet())).use { f ->
                val (first, second) = when (kind) {
                    "door" -> f.door()
                    "bed" -> f.bed()
                    else -> f.doubleChest()
                }
                assertEquals(ActionResult.SUCCESS, f.module.actions.execute(PlaceAction(
                    f.context(f.installer), first.testPosition(), PaperCitadelTypes.REINFORCED)))
                val original = assertNotNull(f.module.citadels.findProtecting(first.testPosition()))
                f.installer.held = f.reinforcementStack(HEAVY)
                dispatch(f.placementListener, f.click(second), f.plugin)
                assertEquals(20.0, original.getComponent(Health::class.java)?.health)
                assertEquals(2, f.installer.held.amount)
                f.installer.sneaking = true
                dispatch(f.placementListener, f.click(second), f.plugin)
                assertTrue(original.isCurrent)
                assertEquals(HEAVY, original.getComponent(CitadelKind::class.java)?.definitionKey)
                assertEquals(original.runtimeId, f.module.citadels.findProtecting(first.testPosition())?.runtimeId)
                assertEquals(original.runtimeId, f.module.citadels.findProtecting(second.testPosition())?.runtimeId)
                assertEquals(1, f.installer.held.amount)
                assertEquals(1, f.droppedItems.size)
            }
        }
    }

    @Test
    fun `offhand cancelled item use and stale interaction item do not upgrade`() {
        for (reason in listOf("offhand", "cancelled", "stale")) fixture().use { f ->
            f.place()
            f.installer.held = f.reinforcementStack(HEAVY)
            val event = f.click(hand = if (reason == "offhand") EquipmentSlot.OFF_HAND else EquipmentSlot.HAND)
            if (reason == "cancelled") event.setUseItemInHand(Event.Result.DENY)
            if (reason == "stale") f.installer.held = TestItemStack(Material.DIRT, 2)
            dispatch(f.placementListener, event, f.plugin)
            assertEquals(20.0, f.health())
            assertEquals(2, f.installer.held.amount)
            assertTrue(f.droppedItems.isEmpty())
            assertTrue(f.installer.messages.isEmpty())
        }
    }

    @Test
    fun `custom refund failures or mutations abort before changing reinforcement or consuming items`() {
        val key = Key("custom", "refund")
        for (reason in listOf("missing", "throw", "empty", "two", "health", "maximum", "item", "owner", "recognition", "block", "replacement")) {
            val types = CitadelTypesSettings.DEFAULT_TYPES.toMutableMap()
            types["reinforced"] = types.getValue("reinforced").copy(item = key)
            PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings(types)).use { f ->
                f.place()
                val original = f.citadel()
                f.installer.held = f.reinforcementStack(HEAVY)
                if (reason != "missing") f.materials.register("custom", TestMaterialRegistry(object : MaterialAdapter {
                    override fun getKey() = key
                    override fun isConsists(itemStack: ItemStack): Boolean {
                        if (reason == "recognition") original.editComponent(CitadelOwner::class.java) { it.playerId = f.stranger.id }
                        return false
                    }
                    override fun isConsists(block: Block) = false
                    override fun createItemStack(amount: Int): ItemStack {
                        when (reason) {
                            "throw" -> error("Provider failed")
                            "empty" -> return TestItemStack(Material.AIR, 1)
                            "two" -> return TestItemStack(Material.IRON_INGOT, 2)
                            "health" -> original.editComponent(Health::class.java) { it.health = 19.0 }
                            "maximum" -> f.module.health.registerDefinition(HEAVY, HealthDefinition(100.0))
                            "item" -> f.installer.held = TestItemStack(Material.DIRT, 2)
                            "owner" -> original.editComponent(CitadelOwner::class.java) { it.playerId = f.stranger.id }
                            "block" -> f.material(Material.PISTON)
                            "replacement" -> {
                                f.manager.removeCitadel(original)
                                f.manager.createCitadel(MutableCitadelDraft(f.position).apply {
                                    addComponent(CitadelKind::class.java, CitadelKind(PaperCitadelTypes.REINFORCED))
                                    addComponent(CitadelOwner::class.java, CitadelOwner(f.stranger.id))
                                    addComponent(Health::class.java, Health(7.0))
                                })
                            }
                        }
                        return TestItemStack(Material.IRON_INGOT, 1)
                    }
                }))
                assertEquals(ActionResult.DENIED, f.module.actions.execute(f.action()), reason)
                assertEquals(PaperCitadelTypes.REINFORCED, f.citadel().getComponent(CitadelKind::class.java)?.definitionKey)
                assertEquals(when (reason) { "health" -> 19.0; "replacement" -> 7.0; else -> 20.0 }, f.health())
                assertEquals(2, f.installer.held.amount)
                assertTrue(f.droppedItems.isEmpty())
            }
        }
    }

    @Test
    fun `custom destination material may register late and upgrade feedback honors configured channels`() {
        val adapter = TestMaterialAdapter(Key("custom", "stronger"))
        val types = CitadelTypesSettings.DEFAULT_TYPES.toMutableMap()
        types[HEAVY.key] = types.getValue(HEAVY.key).copy(item = adapter.getKey())
        for (channel in listOf("CHAT", "NONE")) {
            val messages = PaperMessages.read(YamlConfiguration().apply { set("upgrade.success.channel", channel) })
            PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings(types), messages = messages).use { f ->
                f.place()
                f.installer.held = adapter.createItemStack(2)
                assertEquals(ActionResult.DENIED, f.module.actions.execute(f.action()))
                f.materials.register("custom", TestMaterialRegistry(adapter))
                dispatch(f.placementListener, f.click(), f.plugin)
                assertEquals(30.0, f.health())
                assertEquals(1, f.installer.held.amount)
                assertTrue(adapter.isConsists(f.installer.held))
                assertEquals(if (channel == "CHAT") 1 else 0, f.installer.chatMessages.size)
                assertTrue(f.installer.actionBarMessages.isEmpty())
            }
        }
    }

    @Test
    fun `world or chunk becoming unavailable during refund creation denies without consuming or loading`() {
        val key = Key("custom", "refund")
        val types = CitadelTypesSettings.DEFAULT_TYPES.toMutableMap()
        types["reinforced"] = types.getValue("reinforced").copy(item = key)
        for (unavailable in listOf("world", "chunk", "storage")) {
            PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings(types)).use { f ->
                f.place()
                val original = f.citadel()
                f.installer.held = f.reinforcementStack(HEAVY)
                f.materials.register("custom", TestMaterialRegistry(object : MaterialAdapter {
                    override fun getKey() = key
                    override fun isConsists(itemStack: ItemStack) = false
                    override fun isConsists(block: Block) = false
                    override fun createItemStack(amount: Int): ItemStack {
                        when (unavailable) {
                            "world" -> f.worldAvailable = false
                            "chunk" -> f.worldChunkLoaded = false
                            "storage" -> f.manager.unloadChunk(f.worldId, 0, 0).join()
                        }
                        return TestItemStack(Material.IRON_INGOT, 1)
                    }
                }))
                assertEquals(ActionResult.DENIED, f.module.actions.execute(f.action()), unavailable)
                assertEquals(2, f.installer.held.amount)
                assertTrue(f.droppedItems.isEmpty())
                if (original.isCurrent) assertEquals(20.0, original.getComponent(Health::class.java)?.health)
                f.worldAvailable = true
                f.worldChunkLoaded = true
                if (unavailable == "storage") f.loadChunk(0, 0)
                assertEquals(20.0, f.health())
                assertEquals(PaperCitadelTypes.REINFORCED, f.citadel().getComponent(CitadelKind::class.java)?.definitionKey)
            }
        }
    }

    @Test
    fun `upgrade service lifecycle does not duplicate processors or owner approvals`() {
        fixture().use { f ->
            f.place()
            f.installer.held = f.reinforcementStack(HEAVY)
            f.upgradeService.disable()
            assertEquals(ActionResult.DENIED, f.module.actions.execute(f.action()))
            f.upgradeService.enable()
            f.upgradeService.enable()
            assertEquals(ActionResult.SUCCESS, f.module.actions.execute(f.action()))
            assertEquals(1, f.droppedItems.size)
            assertEquals(1, f.installer.held.amount)
        }
    }

    private fun fixture(dismantleSettings: DismantleSettings = DismantleSettings(), random: () -> Double = { 0.0 }) =
        PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings(),
            dismantleSettings = dismantleSettings, dismantleRandom = random)

    private fun PaperTestFixture.citadel() = assertNotNull(module.citadels.findProtecting(position))
    private fun PaperTestFixture.action(type: Key = HEAVY) = UpgradeAction(context(installer), position, type)
    private fun PaperTestFixture.click(block: Block = block(), hand: EquipmentSlot = EquipmentSlot.HAND) =
        PlayerInteractEvent(installer.player, Action.RIGHT_CLICK_BLOCK, installer.held, block, BlockFace.UP, hand)
    private fun PaperTestFixture.validate(check: (UpgradeAction) -> ActionValidator.Result) =
        object : ActionValidator<UpgradeAction> {
            override val actionClass = UpgradeAction::class.java
            override fun validate(action: UpgradeAction) = check(action)
        }.also(module.actions.validatorCoordinator::registerValidator)

    class Marker : Component() {
        var value = 0
        override fun write(output: DataOutput) = output.writeInt(value)
        override fun read(input: DataInput) { value = input.readInt() }
    }

    private data class RefundCase(val health: Double, val chance: Double, val sample: Double, val drop: Boolean)
    private companion object {
        val HEAVY = Key("fornaxcitadel", "reinforced_heavy")
    }
}
