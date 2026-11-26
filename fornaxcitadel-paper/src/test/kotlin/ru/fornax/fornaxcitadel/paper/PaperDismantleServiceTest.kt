package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.material.MaterialAdapter
import ru.fornax.fornaxcitadel.paper.modules.dismantle.DismantleSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaperDismantleServiceTest {
    @Test
    fun `ownership approves the source player while nonowners and administrators need another authorizer`() {
        PaperTestFixture(permissions = true, dismantleSettings = DismantleSettings(1.0)).use { fixture ->
            fixture.place()
            val actions = fixture.actions
            val owner = DismantleAction(ActionContext(fixture.stranger.id, PlayerSource(fixture.installer.player)), fixture.position)
            val stranger = fixture.action(fixture.stranger)
            fixture.stranger.permissions.add("fornaxcitadel.admin")
            assertTrue(actions.isAllowed(owner))
            assertFalse(actions.isAllowed(stranger))
            assertEquals(ActionResult.DENIED, fixture.actions.execute(stranger))
            assertTrue(fixture.playerBreaks.isEmpty())

            val grant = fixture.validate { ActionValidator.Result.Success }
            val veto = fixture.validate { ActionValidator.Result.Rejected }
            assertEquals(ActionResult.DENIED, fixture.actions.execute(stranger))
            assertTrue(fixture.playerBreaks.isEmpty())
            actions.unregisterValidator(veto)
            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(stranger))
            assertTrue(fixture.playerBreaks.isEmpty())
            assertNull(fixture.citadels.findProtecting(fixture.position))
            assertEquals(Material.STONE, fixture.block().type)
            assertEquals(1, fixture.droppedItems.size)
            actions.unregisterValidator(grant)
        }
    }

    @Test
    fun `successful dismantle preserves the block without damage and handles completion after refund`() {
        PaperTestFixture(permissions = true, dismantleSettings = DismantleSettings(1.0)).use { fixture ->
            fixture.place()
            val original = assertNotNull(fixture.citadels.findProtecting(fixture.position))
            val completed = mutableListOf<DismantleAction>()
            fixture.actions.registerHandler(object : ActionHandler<DismantleAction> {
                override val actionClass = DismantleAction::class.java
                override fun handle(action: DismantleAction) {
                    assertFalse(original.isCurrent)
                    assertEquals(Material.STONE, fixture.block().type)
                    assertEquals(1, fixture.droppedItems.size)
                    completed.add(action)
                }
            })
            fixture.actions.registerValidator(object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction): ActionValidator.Result = error("Dismantle reentered damage")
            })
            fixture.beforeBreakEvent = { error("Dismantle fired a block break event") }
            fixture.afterBreakEvent = { error("Dismantle fired a block break event") }

            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(fixture.action()))
            assertEquals(fixture.position, completed.single().position)
            assertTrue(fixture.playerBreaks.isEmpty())
            assertNull(fixture.citadels.findProtecting(fixture.position))
            val (location, refund) = fixture.droppedItems.single()
            assertEquals(fixture.block().location.add(0.5, 0.5, 0.5), location)
            assertEquals(Material.IRON_INGOT, refund.type)
            assertEquals(1, refund.amount)
            assertEquals(ActionResult.DENIED, fixture.actions.execute(fixture.action()))
            assertTrue(fixture.playerBreaks.isEmpty())
            assertEquals(1, fixture.droppedItems.size)
            assertEquals(1, completed.size)
        }
    }

    @Test
    fun `missing or failing material adapters preserve the reinforcement and block`() {
        for (providerPresent in listOf(false, true)) {
            PaperTestFixture(permissions = true, dismantleSettings = DismantleSettings(0.0),
                citadelSettings = typesWithItem(Key("external", "broken_refund"))).use { fixture ->
                val key = Key("external", "broken_refund")
                if (providerPresent) fixture.materials.register(key.namespace, TestMaterialRegistry(object : MaterialAdapter {
                    override fun getKey() = key
                    override fun isConsists(itemStack: ItemStack) = false
                    override fun isConsists(block: Block) = false
                    override fun breakNaturallyBlock(block: Block, triggerEffect: Boolean) = false
                    override fun createItemStack(amount: Int): ItemStack = error("provider unavailable")
                }))
                fixture.placeWithItem(key)

                assertEquals(ActionResult.DENIED, fixture.actions.execute(fixture.action()))
                assertTrue(fixture.playerBreaks.isEmpty())
                assertTrue(fixture.droppedItems.isEmpty())
                assertNotNull(fixture.citadels.findProtecting(fixture.position))
                assertEquals(Material.STONE, fixture.block().type)
            }
        }
    }

    @Test
    fun `full or absent health guarantees a refund without rolling`() {
        for (withHealth in listOf(false, true)) for (chance in listOf(0.0, 0.5)) {
            PaperTestFixture(dismantleSettings = DismantleSettings(chance),
                dismantleRandom = { error("Guaranteed refund must not roll") }).use { fixture ->
                fixture.place()
                if (!withHealth) {
                    val citadel = assertNotNull(fixture.citadels.findProtecting(fixture.position))
                    assertNotNull(citadel.removeComponent(Health::class.java))
                }

                assertEquals(ActionResult.SUCCESS, fixture.actions.execute(fixture.action()))
                val refund = fixture.droppedItems.single().second
                assertEquals(Material.IRON_INGOT, refund.type)
                assertEquals(1, refund.amount)
            }
        }
    }

    @Test
    fun `damaged reinforcement uses configured chance boundaries and missing metadata never rolls`() {
        for ((chance, sample, refunded) in listOf(
            Triple(0.0, 0.0, false), Triple(1.0, 0.999, true),
            Triple(0.5, 0.499, true), Triple(0.5, 0.5, false)
        )) {
            var rolls = 0
            PaperTestFixture(dismantleSettings = DismantleSettings(chance), dismantleRandom = { rolls++; sample }).use { fixture ->
                fixture.place()
                assertTrue(assertNotNull(fixture.citadels.findProtecting(fixture.position))
                    .editComponent(Health::class.java) { it.health = 99.0 })
                assertEquals(ActionResult.SUCCESS, fixture.actions.execute(fixture.action()))
                assertEquals(if (refunded) 1 else 0, fixture.droppedItems.size)
                assertEquals(if (chance in listOf(0.0, 1.0)) 0 else 1, rolls)
                assertEquals(ActionResult.DENIED, fixture.actions.execute(fixture.action()))
                assertEquals(if (refunded) 1 else 0, fixture.droppedItems.size)
            }
        }
        PaperTestFixture(dismantleSettings = DismantleSettings(1.0),
            dismantleRandom = { error("Missing metadata must not roll") }).use { fixture ->
            fixture.placeWithItem(null)
            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(fixture.action()))
            assertTrue(fixture.droppedItems.isEmpty())
        }
        PaperTestFixture(dismantleSettings = DismantleSettings(0.0)).use { fixture ->
            fixture.placeWithItem(Key("missing", "material"))
            assertTrue(assertNotNull(fixture.citadels.findProtecting(fixture.position))
                .editComponent(Health::class.java) { it.health = 99.0 })
            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(fixture.action()))
            assertTrue(fixture.droppedItems.isEmpty())
        }
    }

    @Test
    fun `dismantling requires a live player in the target world even when an external validator approves`() {
        PaperTestFixture(permissions = false).use { fixture ->
            fixture.place()
            fixture.validate { ActionValidator.Result.Success }
            assertEquals(ActionResult.DENIED, fixture.actions.execute(DismantleAction(ActionContext.SYSTEM, fixture.position)))
            fixture.installer.online = false
            assertEquals(ActionResult.DENIED, fixture.actions.execute(fixture.action()))
            fixture.installer.online = true
            fixture.installer.gameMode = GameMode.SPECTATOR
            assertEquals(ActionResult.DENIED, fixture.actions.execute(fixture.action()))
            fixture.installer.gameMode = GameMode.SURVIVAL
            val otherWorld = paperProxy<World>("other world") { method, _ ->
                if (method.name == "getUID") UUID(99, 1) else unsupported(method)
            }
            fixture.installer.location = Location(otherWorld, 0.0, 64.0, 0.0)
            assertEquals(ActionResult.DENIED, fixture.actions.execute(fixture.action()))
            assertTrue(fixture.playerBreaks.isEmpty())
            assertNotNull(fixture.citadels.findProtecting(fixture.position))
        }
    }

    @Test
    fun `original custom material and owner survive reload and creative placement also refunds`() {
        for (mode in listOf(GameMode.SURVIVAL, GameMode.CREATIVE)) {
            PaperTestFixture(permissions = true, dismantleSettings = DismantleSettings(1.0),
                citadelSettings = typesWithItem(Key("custom", "original_reinforcement"))).use { fixture ->
                val adapter = TestMaterialAdapter(Key("custom", "original_reinforcement"))
                fixture.materials.register("custom", TestMaterialRegistry(adapter))
                fixture.placementService.registerMaterial(adapter.getKey(), PaperCitadelTypes.REINFORCED)
                fixture.installer.gameMode = mode
                fixture.installer.held = adapter.createItemStack(2)
                fixture.placementService.place(fixture.installer.player, fixture.block(), adapter.getKey())
                assertEquals(if (mode == GameMode.CREATIVE) 2 else 1, fixture.installer.held.amount)
                fixture.manager.unloadChunk(fixture.worldId, 0, 0).join()
                fixture.loadChunk(0, 0)
                val restored = assertNotNull(fixture.citadels.findProtecting(fixture.position))
                assertEquals(fixture.installer.id, restored.getComponent(CitadelOwner::class.java)?.playerId)
                assertEquals(adapter.getKey(), fixture.citadelTypes.itemKey(restored))

                assertEquals(ActionResult.SUCCESS, fixture.actions.execute(fixture.action()))
                val returned = fixture.droppedItems.single().second
                assertEquals(1, returned.amount)
                assertTrue(adapter.isConsists(returned))
            }
        }
    }

    @Test
    fun `linked block dismantling preserves both parts and removes the resolved reinforcement`() {
        PaperTestFixture(permissions = true, dismantleSettings = DismantleSettings(1.0)).use { fixture ->
            val (lower, upper) = fixture.door()
            // Existing linked protection is independent of the current solid-block placement filter.
            assertEquals(ActionResult.SUCCESS, fixture.coreGameplay.place.place(PlaceAction(
                fixture.context(fixture.installer), upper.testPosition(), PaperCitadelTypes.REINFORCED)))
            val original = assertNotNull(fixture.citadels.findProtecting(upper.testPosition()))
            assertEquals(lower.testPosition(), original.position)

            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(fixture.action(position = upper.testPosition())))
            assertTrue(fixture.playerBreaks.isEmpty())
            assertEquals(Material.OAK_DOOR, lower.type)
            assertEquals(Material.OAK_DOOR, upper.type)
            assertFalse(original.isCurrent)
            assertNull(fixture.manager.findCitadel(fixture.worldId, lower.x, lower.y, lower.z))
            assertEquals(upper.location.add(0.5, 0.5, 0.5), fixture.droppedItems.single().first)
        }
    }

    @Test
    fun `disabled dismantle denies execution and preserves owner approval`() {
        PaperTestFixture(permissions = true).use { fixture ->
            fixture.place()
            val action = fixture.action()
            val actions = fixture.actions
            assertTrue(actions.isAllowed(action))
            val grant = fixture.validate { ActionValidator.Result.Success }
            fixture.dismantleService.disable()
            fixture.dismantleService.disable()
            assertTrue(actions.unregisterValidator(grant))
            assertTrue(actions.isAllowed(action))
            assertEquals(ActionResult.DENIED, fixture.actions.execute(action))
            fixture.dismantleService.enable()
            fixture.dismantleService.enable()
            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(action))
            assertEquals(Material.IRON_INGOT, fixture.droppedItems.single().second.type)
        }
    }

    private fun PaperTestFixture.action(player: TestPlayer = installer, position: CitadelPosition = this.position) =
        DismantleAction(context(player), position)

    private fun PaperTestFixture.placeWithItem(key: Key?) {
        assertEquals(ActionResult.SUCCESS, actions.execute(
            PlaceAction(context(installer), position, PaperCitadelTypes.REINFORCED)))
        if (key == null) assertNotNull(manager.findCitadel(worldId, position.x, position.y, position.z)).removeComponent(CitadelKind::class.java)
    }

    private fun PaperTestFixture.validate(check: (DismantleAction) -> ActionValidator.Result): ActionValidator<DismantleAction> =
        object : ActionValidator<DismantleAction> {
            override val actionClass = DismantleAction::class.java
            override fun validate(action: DismantleAction) = check(action)
        }.also(actions::registerValidator)
}
