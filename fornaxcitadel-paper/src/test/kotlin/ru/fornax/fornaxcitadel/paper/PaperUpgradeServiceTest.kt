package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.Event
import org.bukkit.event.block.Action as BukkitAction
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.ReadableComponent
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthDefinition
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.config.PaperMessages
import ru.fornax.fornaxcitadel.paper.material.MaterialAdapter
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidResult
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidSettings
import ru.fornax.fornaxcitadel.paper.modules.dismantle.DismantleSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.BlockListType
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.placement.PlacementSettings
import java.io.DataInput
import java.io.DataOutput
import kotlin.test.*

class PaperUpgradeServiceTest {
    @Test
    fun `direct upgrade recognizes the held material once`() {
        var heldLookups = 0
        fixture(serviceMaterials = { materials ->
            object : MaterialRegistry by materials {
                override fun getTypeOrNull(stack: ItemStack): MaterialAdapter? {
                    heldLookups++
                    return materials.getTypeOrNull(stack)
                }
            }
        }).use { f ->
            f.place()
            f.installer.held = f.reinforcementStack(HEAVY)
            val action = f.action()
            assertTrue(f.actions.isAllowed(action))
            assertEquals(0, heldLookups)

            assertEquals(ActionResult.SUCCESS, f.upgradeService.upgrade(action))

            assertEquals(1, heldLookups)
            assertEquals(HEAVY, f.citadel().getComponent(CitadelKind::class.java)?.definitionKey)
            assertEquals(30.0, f.health())
            assertEquals(1, f.installer.held.amount)
            assertEquals(1, f.droppedItems.size)
        }
    }

    @Test
    fun `upgrade preserves identity owner and extra components and completes before notifying handlers`() {
        for (mode in listOf(GameMode.SURVIVAL, GameMode.CREATIVE)) for (amount in listOf(1, 3)) {
            fixture(dismantleSettings = DismantleSettings(0.0)).use { f ->
                f.place()
                val original = f.citadel()
                val owner = original.getComponent(CitadelOwner::class.java)
                f.manager.registerComponent(Key("test", "marker"), Marker::class.java)
                val marker = Marker().apply { value = 42 }
                assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).putComponent(Marker::class.java, marker)
                original.editComponent(Maturing::class.java) { it.startedAtMillis = 1 }
                f.installer.gameMode = mode
                f.installer.held = f.reinforcementStack(HEAVY, amount)
                var completed = 0
                f.actions.registerHandler(object : ActionHandler<ru.fornax.fornaxcitadel.core.systems.action.Action> {
                    override val actionClass = Action::class.java
                    override fun handle(action: ru.fornax.fornaxcitadel.core.systems.action.Action) {
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
                assertEquals(ActionResult.DENIED, f.actions.execute(f.action()))
                assertEquals(1, completed)
                assertEquals(1, f.droppedItems.size)
            }
        }
    }

    @Test
    fun `upgrade runs only destination updates in definition order on the existing citadel`() {
        PaperTestFixture(load = false, permissions = true, registerTypes = false,
            citadelSettings = CitadelTypesSettings()).use { f ->
            val calls = mutableListOf<String>()
            val marker = Marker()
            fun initializer(label: String) = object : CitadelInitializer {
                override fun initialize(draft: CitadelDraft) {
                    calls.add("initialize $label")
                    draft.addComponent(Marker::class.java, marker)
                }

                override fun update(handle: CitadelHandle) {
                    calls.add("update $label")
                    assertEquals(HEAVY, handle.getComponent(CitadelKind::class.java)?.definitionKey)
                    assertTrue(handle.editComponent(Marker::class.java) { it.value++ })
                }
            }

            f.manager.registerComponent(Key("test", "marker"), Marker::class.java)
            f.coreGameplay.registerDefinition(CitadelDefinition(PaperCitadelTypes.REINFORCED,
                listOf(initializer("source")), 2, Key("minecraft", "iron_ingot")))
            f.coreGameplay.registerDefinition(CitadelDefinition(HEAVY,
                listOf(initializer("first"), initializer("second")), 3, Key("minecraft", "diamond")))
            f.coreGameplay.health.registerDefinition(HEAVY, HealthDefinition(30.0))
            f.loadChunk(0, 0)
            f.place()
            val original = f.citadel()
            f.installer.held = f.reinforcementStack(HEAVY)

            assertEquals(ActionResult.SUCCESS, f.actions.execute(f.action()))

            assertEquals(listOf("initialize source", "update first", "update second"), calls)
            assertEquals(original.runtimeId, f.citadel().runtimeId)
            assertSame(marker, original.getComponent(Marker::class.java))
            assertEquals(2, marker.value)
            assertNull(original.getComponent(Health::class.java))
            assertNull(original.getComponent(Maturing::class.java))
        }
    }

    @Test
    fun `known levels alone determine improvement regardless of maximum or damaged HP`() {
        for (savedHealth in listOf(1.0, 20.0)) for ((level, maximum, allowed) in listOf(
            Triple(3, 20.0, true), Triple(2, 30.0, false), Triple(3, 30.0, true),
            Triple(3, 10.0, true), Triple(1, 100.0, false), Triple(2, 20.0, false)
        )) {
            val types = CitadelTypesSettings.DEFAULT_TYPES.toMutableMap()
            types[HEAVY.key] = types.getValue(HEAVY.key).copy(lvl = level, health = HealthDefinition(maximum))
            PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings(types)).use { f ->
                f.place()
                val original = f.citadel()
                original.editComponent(Health::class.java) { it.health = savedHealth }
                val kind = original.getComponent(CitadelKind::class.java)
                val health = original.getComponent(Health::class.java)
                val maturing = original.getComponent(Maturing::class.java)
                f.installer.held = f.reinforcementStack(HEAVY)

                assertEquals(if (allowed) ActionResult.SUCCESS else ActionResult.DENIED,
                    f.actions.execute(f.action()), "$level / $maximum from $savedHealth HP")

                assertEquals(if (allowed) maximum else savedHealth, f.health())
                assertEquals(if (allowed) 1 else 2, f.installer.held.amount)
                assertTrue(original.isCurrent)
                if (!allowed) {
                    assertSame(kind, original.getComponent(CitadelKind::class.java))
                    assertSame(health, original.getComponent(Health::class.java))
                    assertSame(maturing, original.getComponent(Maturing::class.java))
                    assertTrue(f.droppedItems.isEmpty())
                }
            }
        }
    }

    @Test
    fun `missing kind or definition prevents upgrade regardless of saved HP`() {
        for (missing in listOf("kind", "definition")) for (savedHealth in listOf(80.0, 30.0, 10.0)) fixture().use { f ->
            f.place()
            val original = f.citadel()
            val owner = original.getComponent(CitadelOwner::class.java)
            when (missing) {
                "kind" -> assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(CitadelKind::class.java)
                "definition" -> original.editComponent(CitadelKind::class.java) { it.definitionKey = Key("test", "missing") }
            }
            original.editComponent(Health::class.java) { it.health = savedHealth }
            val oldKind = original.getComponent(CitadelKind::class.java)?.definitionKey
            assertNull(f.citadelTypes.level(original))
            f.installer.held = f.reinforcementStack(HEAVY)

            assertFalse(f.actions.isAllowed(f.action()))
            assertEquals(ActionResult.DENIED, f.actions.execute(f.action()), "$missing / $savedHealth")

            assertTrue(original.isCurrent)
            assertEquals(original.runtimeId, f.citadel().runtimeId)
            assertSame(owner, original.getComponent(CitadelOwner::class.java))
            assertEquals(oldKind, original.getComponent(CitadelKind::class.java)?.definitionKey)
            assertEquals(savedHealth, f.health())
            assertEquals(2, f.installer.held.amount)
            assertTrue(f.droppedItems.isEmpty())
        }
    }

    @Test
    fun `known level still takes priority when the old maximum is absent`() {
        fixture(DismantleSettings(0.0)).use { f ->
            f.place()
            val original = f.citadel()
            f.coreGameplay.health.unregisterDefinition(PaperCitadelTypes.REINFORCED)
            original.editComponent(Health::class.java) { it.health = 80.0 }
            f.installer.held = f.reinforcementStack(HEAVY)

            assertEquals(ActionResult.SUCCESS, f.actions.execute(f.action()))

            assertTrue(original.isCurrent)
            assertEquals(HEAVY, original.getComponent(CitadelKind::class.java)?.definitionKey)
            assertEquals(30.0, f.health())
            assertEquals(1, f.installer.held.amount)
            assertTrue(f.droppedItems.isEmpty())
            assertEquals(f.installer.id, original.getComponent(CitadelOwner::class.java)?.playerId)
        }
    }

    @Test
    fun `unranked definitions cannot be the source or destination of an upgrade`() {
        val unranked = Key("external", "without_level")
        for ((source, destination) in listOf(unranked to HEAVY, PaperCitadelTypes.REINFORCED to unranked)) {
            fixture().use { f ->
                f.coreGameplay.registerDefinition(CitadelDefinition(unranked, emptyList(),
                    itemKey = Key("minecraft", "prismarine_shard")))
                f.place(source)
                f.installer.held = f.reinforcementStack(destination)
                val action = f.action(destination)

                assertFalse(f.actions.isAllowed(action))
                assertEquals(ActionResult.DENIED, f.actions.execute(action))
                assertEquals(ActionResult.DENIED, f.upgradeService.upgrade(action))
                assertEquals(source, f.citadel().getComponent(CitadelKind::class.java)?.definitionKey)
                assertEquals(2, f.installer.held.amount)
                assertTrue(f.droppedItems.isEmpty())
            }
        }
    }

    @Test
    fun `destination without maximum accepts a lower source level and removes saved HP`() {
        fixture().use { f ->
            f.place()
            val original = f.citadel()
            val owner = original.getComponent(CitadelOwner::class.java)
            f.coreGameplay.health.unregisterDefinition(HEAVY)
            assertFalse(f.coreGameplay.health.isInvulnerable(HEAVY))
            f.installer.held = f.reinforcementStack(HEAVY)

            assertEquals(ActionResult.SUCCESS, f.actions.execute(f.action()))

            assertTrue(original.isCurrent)
            assertEquals(original.runtimeId, f.citadel().runtimeId)
            assertSame(owner, original.getComponent(CitadelOwner::class.java))
            assertEquals(1, f.installer.held.amount)
            assertEquals(HEAVY, original.getComponent(CitadelKind::class.java)?.definitionKey)
            assertEquals(3, f.citadelTypes.level(original))
            assertNull(original.getComponent(Health::class.java))
            assertNull(f.coreGameplay.health.currentHealth(original))
            assertEquals(1, f.droppedItems.size)
        }
    }

    @Test
    fun `missing kind and health stay absent after a rejected upgrade`() = fixture().use { f ->
        f.place()
        val original = f.citadel()
        val owner = original.getComponent(CitadelOwner::class.java)
        assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(CitadelKind::class.java)
        assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(Health::class.java)
        val destination = Key("fornaxcitadel", "reinforced_light")
        f.installer.held = f.reinforcementStack(destination)

        assertEquals(ActionResult.DENIED, f.actions.execute(f.action(destination)))

        assertTrue(original.isCurrent)
        assertEquals(original.runtimeId, f.citadel().runtimeId)
        assertSame(owner, original.getComponent(CitadelOwner::class.java))
        assertNull(original.getComponent(CitadelKind::class.java))
        assertNull(original.getComponent(Health::class.java))
        assertEquals(2, f.installer.held.amount)
        assertTrue(f.droppedItems.isEmpty())
    }

    @Test
    fun `invalid saved HP permits upgrade but receives only the configured refund chance`() {
        for (savedHealth in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            for (chance in listOf(0.0, 1.0)) for (hasMaximum in listOf(true, false)) {
                fixture(DismantleSettings(chance)) { error("Zero or certain refunds must not roll") }.use { f ->
                    f.place()
                    val original = f.citadel()
                    val owner = original.getComponent(CitadelOwner::class.java)
                    original.editComponent(Health::class.java) { it.health = savedHealth }
                    if (!hasMaximum) f.coreGameplay.health.unregisterDefinition(HEAVY)
                    f.installer.held = f.reinforcementStack(HEAVY)

                    assertEquals(ActionResult.SUCCESS, f.actions.execute(f.action()),
                        "$savedHealth / chance $chance / maximum $hasMaximum")

                    assertTrue(original.isCurrent)
                    assertEquals(original.runtimeId, f.citadel().runtimeId)
                    assertSame(owner, original.getComponent(CitadelOwner::class.java))
                    assertEquals(HEAVY, original.getComponent(CitadelKind::class.java)?.definitionKey)
                    assertEquals(3, f.citadelTypes.level(original))
                    if (hasMaximum) assertEquals(30.0, f.health())
                    else assertNull(original.getComponent(Health::class.java))
                    assertEquals(1, f.installer.held.amount)
                    assertEquals(if (chance == 1.0) 1 else 0, f.droppedItems.size)
                    if (chance == 1.0) {
                        assertEquals(Material.IRON_INGOT, f.droppedItems.single().second.type)
                        assertEquals(1, f.droppedItems.single().second.amount)
                    }
                }
            }
        }
    }

    @Test
    fun `unknown destination is denied with or without a known source level`() {
        for (knownLevel in listOf(true, false)) fixture().use { f ->
            f.place()
            val original = f.citadel()
            if (!knownLevel) assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(CitadelKind::class.java)
            val kind = original.getComponent(CitadelKind::class.java)
            val health = original.getComponent(Health::class.java)
            val maturing = original.getComponent(Maturing::class.java)
            val owner = original.getComponent(CitadelOwner::class.java)
            f.installer.held = f.reinforcementStack(HEAVY)

            assertEquals(ActionResult.DENIED, f.actions.execute(f.action(Key("test", "missing"))), knownLevel.toString())

            assertTrue(original.isCurrent)
            assertSame(kind, original.getComponent(CitadelKind::class.java))
            assertSame(health, original.getComponent(Health::class.java))
            assertSame(maturing, original.getComponent(Maturing::class.java))
            assertSame(owner, original.getComponent(CitadelOwner::class.java))
            assertEquals(20.0, f.health())
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
                assertEquals(ActionResult.SUCCESS, f.actions.execute(f.action()))
                assertEquals(if (drop) 1 else 0, f.droppedItems.size)
                assertEquals(if (health < 20 && chance > 0 && chance < 1) 1 else 0, rolls)
            }
        }
        for (missing in listOf("maximum", "health", "item")) {
            fixture(DismantleSettings(0.0)) { error("Absent or guaranteed refund must not roll") }.use { f ->
                f.place()
                when (missing) {
                    "maximum" -> f.coreGameplay.health.unregisterDefinition(PaperCitadelTypes.REINFORCED)
                    "health" -> assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(Health::class.java)
                    "item" -> {
                        val source = CitadelDefinition(Key("external", "without_item"), emptyList(), level = 1)
                        f.coreGameplay.registerDefinition(source)
                        f.citadel().editComponent(CitadelKind::class.java) { it.definitionKey = source.key }
                    }
                }
                f.installer.held = f.reinforcementStack(HEAVY)
                assertEquals(ActionResult.SUCCESS, f.actions.execute(f.action()), missing)
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
                assertEquals(ActionResult.DENIED, f.actions.execute(f.action()))
                f.installer.permissions.add(permission)
            }
            f.stranger.held = f.reinforcementStack(HEAVY)
            f.stranger.permissions.add("fornaxcitadel.admin")
            val request = UpgradeAction(f.context(f.stranger), f.position, HEAVY)
            assertEquals(ActionResult.DENIED, f.actions.execute(request))
            f.validate { ActionValidator.Result.Success }
            val veto = f.validate { ActionValidator.Result.Rejected }
            assertEquals(ActionResult.DENIED, f.actions.execute(request))
            assertEquals(20.0, f.health())
            assertEquals(2, f.stranger.held.amount)
            assertTrue(f.droppedItems.isEmpty())
            f.actions.unregisterValidator(veto)
            assertEquals(ActionResult.SUCCESS, f.actions.execute(request))
            assertEquals(f.installer.id, f.citadel().getComponent(CitadelOwner::class.java)?.playerId)
        }
    }

    @Test
    fun `invulnerable destination needs admin and a higher level may restore damageable health`() {
        val types = CitadelTypesSettings.DEFAULT_TYPES.toMutableMap()
        types[HEAVY.key] = types.getValue(HEAVY.key).copy(lvl = 1000, health = HealthDefinition(1000.0))
        PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings(types)).use { f ->
            f.place()
            val original = f.citadel()
            val health = original.getComponent(Health::class.java)
            val maturing = original.getComponent(Maturing::class.java)
            f.installer.held = f.reinforcementStack(PaperCitadelTypes.INVULNERABLE)
            assertEquals(ActionResult.DENIED, f.actions.execute(f.action(PaperCitadelTypes.INVULNERABLE)))
            assertEquals(20.0, f.health())
            assertSame(health, original.getComponent(Health::class.java))
            assertSame(maturing, original.getComponent(Maturing::class.java))
            assertEquals(PaperCitadelTypes.REINFORCED, original.getComponent(CitadelKind::class.java)?.definitionKey)
            assertEquals(2, f.installer.held.amount)
            assertTrue(f.droppedItems.isEmpty())

            f.installer.permissions.add("fornaxcitadel.admin")
            assertEquals(ActionResult.SUCCESS, f.actions.execute(f.action(PaperCitadelTypes.INVULNERABLE)))
            assertNull(original.getComponent(Health::class.java))
            assertEquals(PaperCitadelTypes.INVULNERABLE, original.getComponent(CitadelKind::class.java)?.definitionKey)
            assertEquals(1, f.droppedItems.size)

            f.installer.permissions.remove("fornaxcitadel.admin")
            f.installer.held = f.reinforcementStack(HEAVY)
            assertEquals(ActionResult.SUCCESS, f.actions.execute(f.action()))
            assertTrue(original.isCurrent)
            assertEquals(HEAVY, original.getComponent(CitadelKind::class.java)?.definitionKey)
            assertEquals(1000.0, f.health())
            assertEquals(1, f.installer.held.amount)
            assertEquals(f.installer.id, original.getComponent(CitadelOwner::class.java)?.playerId)
        }
        fixture().use { f ->
            f.place()
            val original = f.citadel()
            val owner = original.getComponent(CitadelOwner::class.java)
            assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(CitadelKind::class.java)
            val maturing = original.getComponent(Maturing::class.java)
            f.installer.permissions.add("fornaxcitadel.admin")
            f.installer.held = f.reinforcementStack(PaperCitadelTypes.INVULNERABLE)

            assertEquals(ActionResult.DENIED, f.actions.execute(f.action(PaperCitadelTypes.INVULNERABLE)))

            assertTrue(original.isCurrent)
            assertEquals(original.runtimeId, f.citadel().runtimeId)
            assertSame(owner, original.getComponent(CitadelOwner::class.java))
            assertNull(original.getComponent(CitadelKind::class.java))
            assertEquals(20.0, f.health())
            assertSame(maturing, original.getComponent(Maturing::class.java))
            assertEquals(2, f.installer.held.amount)
            assertTrue(f.droppedItems.isEmpty())
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
                f.actions.post(f.action())
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
                f.actions.registerHandler(object : ActionHandler<UpgradeAction> {
                    override val actionClass = UpgradeAction::class.java
                    override fun handle(action: UpgradeAction) { completed++ }
                })
                assertEquals(1, f.actions.drainPosted())
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
            assertEquals(ActionResult.SUCCESS, f.actions.execute(f.action(PaperCitadelTypes.MATURING)))
            val started = original.getComponent(Maturing::class.java)!!.startedAtMillis
            assertTrue(started > 1L)
            assertFalse(f.coreGameplay.maturation.isMature(original))
            f.manager.unloadChunk(f.worldId, 0, 0).join()
            f.loadChunk(0, 0)
            val restored = f.citadel()
            assertEquals(PaperCitadelTypes.MATURING, restored.getComponent(CitadelKind::class.java)?.definitionKey)
            assertEquals(40.0, f.health())
            assertEquals(started, restored.getComponent(Maturing::class.java)?.startedAtMillis)
            assertEquals(f.installer.id, restored.getComponent(CitadelOwner::class.java)?.playerId)
            assertFalse(f.coreGameplay.maturation.isMature(restored))
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
            assertEquals(ActionResult.SUCCESS, f.actions.execute(f.action(PaperCitadelTypes.MATURING)))
            val target = f.citadel()
            // The new base duration is 600 seconds; gold's x2 multiplier makes it 1200 seconds.
            target.editComponent(Maturing::class.java) { it.startedAtMillis = System.currentTimeMillis() - 700_000L }
            val info = assertIs<AcidResult.Information>(acid.inspect(f.installer.player, f.block()))
            assertEquals(4, info.tier)
            assertFalse(info.mature)
            assertTrue(f.coreGameplay.maturation.progress(target) in 0.58..0.60)
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
                assertEquals(ActionResult.SUCCESS, f.actions.execute(PlaceAction(
                    f.context(f.installer), first.testPosition(), PaperCitadelTypes.REINFORCED)))
                val original = assertNotNull(f.citadels.findProtecting(first.testPosition()))
                f.installer.held = f.reinforcementStack(HEAVY)
                dispatch(f.placementListener, f.click(second), f.plugin)
                assertEquals(20.0, original.getComponent(Health::class.java)?.health)
                assertEquals(2, f.installer.held.amount)
                f.installer.sneaking = true
                dispatch(f.placementListener, f.click(second), f.plugin)
                assertTrue(original.isCurrent)
                assertEquals(HEAVY, original.getComponent(CitadelKind::class.java)?.definitionKey)
                assertEquals(original.runtimeId, f.citadels.findProtecting(first.testPosition())?.runtimeId)
                assertEquals(original.runtimeId, f.citadels.findProtecting(second.testPosition())?.runtimeId)
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
    fun `custom refund failures abort before changing reinforcement or consuming items`() {
        val key = Key("custom", "refund")
        for (reason in listOf("missing", "throw", "empty", "two")) {
            val types = CitadelTypesSettings.DEFAULT_TYPES.toMutableMap()
            types["reinforced"] = types.getValue("reinforced").copy(item = key)
            PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings(types)).use { f ->
                f.place()
                f.installer.held = f.reinforcementStack(HEAVY)
                if (reason != "missing") f.materials.register("custom", TestMaterialRegistry(object : MaterialAdapter {
                    override fun getKey() = key
                    override fun isConsists(itemStack: ItemStack) = false
                    override fun isConsists(block: Block) = false
                    override fun breakNaturallyBlock(block: Block, triggerEffect: Boolean) = false
                    override fun createItemStack(amount: Int): ItemStack = when (reason) {
                        "throw" -> error("Provider failed")
                        "empty" -> TestItemStack(Material.AIR, 1)
                        else -> TestItemStack(Material.IRON_INGOT, 2)
                    }
                }))
                assertEquals(ActionResult.DENIED, f.actions.execute(f.action()), reason)
                assertEquals(PaperCitadelTypes.REINFORCED, f.citadel().getComponent(CitadelKind::class.java)?.definitionKey)
                assertEquals(20.0, f.health())
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
                assertEquals(ActionResult.DENIED, f.actions.execute(f.action()))
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
    fun `disabled upgrade denies execution and resumes without duplicate refunds`() {
        fixture().use { f ->
            f.place()
            f.installer.held = f.reinforcementStack(HEAVY)
            f.upgradeService.disable()
            assertEquals(ActionResult.DENIED, f.actions.execute(f.action()))
            f.upgradeService.enable()
            f.upgradeService.enable()
            assertEquals(ActionResult.SUCCESS, f.actions.execute(f.action()))
            assertEquals(1, f.droppedItems.size)
            assertEquals(1, f.installer.held.amount)
        }
    }

    private fun fixture(
        dismantleSettings: DismantleSettings = DismantleSettings(),
        serviceMaterials: (MaterialRegistry) -> MaterialRegistry = { it },
        random: () -> Double = { 0.0 }
    ) = PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings(),
        dismantleSettings = dismantleSettings, dismantleRandom = random, serviceMaterials = serviceMaterials)

    private fun PaperTestFixture.citadel() = assertNotNull(citadels.findProtecting(position))
    private fun PaperTestFixture.action(type: Key = HEAVY) = UpgradeAction(context(installer), position, type)
    private fun PaperTestFixture.click(block: Block = block(), hand: EquipmentSlot = EquipmentSlot.HAND) =
        PlayerInteractEvent(installer.player, BukkitAction.RIGHT_CLICK_BLOCK, installer.held, block, BlockFace.UP, hand)
    private fun PaperTestFixture.validate(check: (UpgradeAction) -> ActionValidator.Result) =
        object : ActionValidator<UpgradeAction> {
            override val actionClass = UpgradeAction::class.java
            override fun validate(action: UpgradeAction) = check(action)
        }.also(actions::registerValidator)

    interface ReadableMarker : ReadableComponent {
        val value: Int
    }

    class Marker : Component<ReadableMarker>(), ReadableMarker {
        override val readable: ReadableMarker get() = this
        override var value = 0
        override fun write(output: DataOutput) = output.writeInt(value)
        override fun read(input: DataInput) { value = input.readInt() }
    }

    private data class RefundCase(val health: Double, val chance: Double, val sample: Double, val drop: Boolean)
    private companion object {
        val HEAVY = Key("fornaxcitadel", "reinforced_heavy")
    }
}
