package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleListener
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.ReinforcementItem
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.material.MaterialAdapter
import ru.fornax.fornaxcitadel.paper.modules.dismantle.DismantleSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperDismantleServiceTest {
    @Test
    fun `ownership approves the source player while nonowners and administrators need another authorizer`() {
        PaperTestFixture(permissions = true, dismantleSettings = DismantleSettings(1.0)).use { fixture ->
            fixture.place()
            val coordinator = fixture.module.actions.validatorCoordinator
            val owner = DismantleAction(ActionContext(fixture.stranger.id, PlayerSource(fixture.installer.player)), fixture.position)
            val stranger = fixture.action(fixture.stranger)
            fixture.stranger.permissions.add("fornaxcitadel.admin")
            assertSame(ActionValidator.Result.Success, coordinator.validate(owner))
            assertSame(ActionValidator.Result.Neutral, coordinator.validate(stranger))
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(stranger))
            assertTrue(fixture.playerBreaks.isEmpty())

            val grant = fixture.validate { ActionValidator.Result.Success }
            val veto = fixture.validate { ActionValidator.Result.Rejected }
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(stranger))
            assertTrue(fixture.playerBreaks.isEmpty())
            coordinator.unregisterValidator(veto)
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(stranger))
            assertSame(fixture.stranger.player, fixture.playerBreaks.single().first)
            assertEquals(1, fixture.droppedItems.size)
            coordinator.unregisterValidator(grant)
        }
    }

    @Test
    fun `successful dismantle breaks once without damage reentry and publishes dismantled before refund`() {
        PaperTestFixture(permissions = true, dismantleSettings = DismantleSettings(1.0)).use { fixture ->
            fixture.place()
            val original = assertNotNull(fixture.module.citadels.findProtecting(fixture.position))
            val causes = mutableListOf<RemovalCause>()
            fixture.module.lifecycle.addListener(object : CitadelLifecycleListener {
                override fun onRemoved(position: CitadelPosition, runtimeId: Int, cause: RemovalCause) {
                    assertFalse(original.isCurrent)
                    assertEquals(Material.AIR, fixture.block().type)
                    assertTrue(fixture.droppedItems.isEmpty())
                    causes.add(cause)
                }
            })
            fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction): ActionValidator.Result = error("Dismantle reentered damage")
            })
            fixture.beforeBreakEvent = {
                assertTrue(original.isCurrent)
                assertEquals(100.0, fixture.health())
                assertFalse(fixture.dismantleService.consumeBreakEvent(fixture.stranger.player, it.block))
                assertFalse(fixture.dismantleService.consumeBreakEvent(it.player, fixture.block(3, 64, 2)))
            }
            fixture.afterBreakEvent = {
                assertFalse(it.isCancelled)
                assertTrue(original.isCurrent)
                assertFalse(fixture.dismantleService.consumeBreakEvent(it.player, it.block))
            }

            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(fixture.action()))
            assertEquals(listOf(RemovalCause.DISMANTLED), causes)
            assertEquals(1, fixture.playerBreaks.size)
            val (location, refund) = fixture.droppedItems.single()
            assertEquals(fixture.block().location.add(0.5, 0.5, 0.5), location)
            assertEquals(Material.IRON_INGOT, refund.type)
            assertEquals(1, refund.amount)
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(fixture.action()))
            assertEquals(1, fixture.playerBreaks.size)
            assertEquals(1, fixture.droppedItems.size)
        }
    }

    @Test
    fun `cancelled failed and throwing physical breaks preserve reinforcement and always clear the bypass`() {
        for (failure in listOf("cancel before", "cancel after", "false", "throw")) {
            PaperTestFixture(permissions = true, dismantleSettings = DismantleSettings(1.0)).use { fixture ->
                fixture.place()
                val original = assertNotNull(fixture.module.citadels.findProtecting(fixture.position))
                val removals = mutableListOf<RemovalCause>()
                fixture.module.lifecycle.addListener(object : CitadelLifecycleListener {
                    override fun onRemoved(position: CitadelPosition, runtimeId: Int, cause: RemovalCause) {
                        removals.add(cause)
                    }
                })
                when (failure) {
                    "cancel before" -> fixture.beforeBreakEvent = { it.isCancelled = true }
                    "cancel after" -> fixture.afterBreakEvent = { it.isCancelled = true }
                    "false" -> fixture.breakBlockReturnsFalse = true
                    "throw" -> fixture.breakBlockFailure = IllegalStateException("physical break failed")
                }
                if (failure == "throw") {
                    assertFailsWith<IllegalStateException> { fixture.module.actions.execute(fixture.action()) }
                } else assertEquals(ActionResult.DENIED, fixture.module.actions.execute(fixture.action()), failure)

                assertTrue(original.isCurrent, failure)
                assertEquals(100.0, fixture.health())
                assertEquals(Material.STONE, fixture.block().type)
                assertTrue(fixture.droppedItems.isEmpty())
                assertTrue(removals.isEmpty())
                assertFalse(fixture.dismantleService.consumeBreakEvent(fixture.installer.player, fixture.block()))
                fixture.beforeBreakEvent = {}
                fixture.afterBreakEvent = {}
                fixture.breakBlockReturnsFalse = false
                fixture.breakBlockFailure = null
                val ordinary = BlockBreakEvent(fixture.block(), fixture.installer.player)
                dispatch(fixture.breakingListener, ordinary, fixture.plugin)
                assertTrue(ordinary.isCancelled)
                assertEquals(99.0, fixture.health())
                assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(fixture.action()))
                assertEquals(1, fixture.droppedItems.size)
            }
        }
    }

    @Test
    fun `missing or failing material adapters reject before any physical break`() {
        for (providerPresent in listOf(false, true)) {
            PaperTestFixture(permissions = true, dismantleSettings = DismantleSettings(0.0)).use { fixture ->
                val key = Key("external", "broken_refund")
                if (providerPresent) fixture.materials.register(key.namespace, TestMaterialRegistry(object : MaterialAdapter {
                    override fun getKey() = key
                    override fun isConsists(itemStack: ItemStack) = false
                    override fun isConsists(block: Block) = false
                    override fun createItemStack(amount: Int): ItemStack = error("provider unavailable")
                }))
                fixture.placeWithItem(key)

                assertEquals(ActionResult.DENIED, fixture.module.actions.execute(fixture.action()))
                assertTrue(fixture.playerBreaks.isEmpty())
                assertTrue(fixture.droppedItems.isEmpty())
                assertNotNull(fixture.module.citadels.findProtecting(fixture.position))
                assertEquals(Material.STONE, fixture.block().type)
            }
        }
    }

    @Test
    fun `full or absent health guarantees a refund without rolling but cancellation still prevents it`() {
        for (withHealth in listOf(false, true)) for (chance in listOf(0.0, 0.5)) {
            PaperTestFixture(dismantleSettings = DismantleSettings(chance),
                dismantleRandom = { error("Guaranteed refund must not roll") }).use { fixture ->
                fixture.place()
                val original = assertNotNull(fixture.module.citadels.findProtecting(fixture.position))
                if (!withHealth) assertNotNull(fixture.manager.removeComponent(
                    Health::class.java, fixture.worldId, fixture.position.x, fixture.position.y, fixture.position.z))
                fixture.beforeBreakEvent = { it.isCancelled = true }

                assertEquals(ActionResult.DENIED, fixture.module.actions.execute(fixture.action()))
                assertTrue(original.isCurrent)
                assertTrue(fixture.droppedItems.isEmpty())
                fixture.beforeBreakEvent = {}

                assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(fixture.action()))
                val refund = fixture.droppedItems.single().second
                assertEquals(Material.IRON_INGOT, refund.type)
                assertEquals(1, refund.amount)
            }
        }
    }

    @Test
    fun `refund chance uses health captured before material preparation and the physical break`() {
        for (initiallyFull in listOf(false, true)) for (mutationStage in listOf("material", "break")) {
            var rolls = 0
            PaperTestFixture(dismantleSettings = DismantleSettings(0.5),
                dismantleRandom = { rolls++; 0.75 }).use { fixture ->
                val key = Key("custom", "health_changing_refund")
                fixture.placeWithItem(key)
                val original = assertNotNull(fixture.module.citadels.findProtecting(fixture.position))
                if (!initiallyFull) assertTrue(original.editComponent(Health::class.java) { it.health = 99.0 })
                val changeHealth = {
                    assertTrue(original.editComponent(Health::class.java) {
                        it.health = if (initiallyFull) 99.0 else it.maxHealth
                    })
                }
                fixture.materials.register(key.namespace, TestMaterialRegistry(object : MaterialAdapter {
                    override fun getKey() = key
                    override fun isConsists(itemStack: ItemStack) = false
                    override fun isConsists(block: Block) = false
                    override fun createItemStack(amount: Int): ItemStack {
                        if (mutationStage == "material") changeHealth()
                        return TestItemStack(Material.IRON_INGOT, amount)
                    }
                }))
                if (mutationStage == "break") fixture.beforeBreakEvent = { changeHealth() }

                assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(fixture.action()))
                assertEquals(if (initiallyFull) 1 else 0, fixture.droppedItems.size)
                assertEquals(if (initiallyFull) 0 else 1, rolls)
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
                assertTrue(assertNotNull(fixture.module.citadels.findProtecting(fixture.position))
                    .editComponent(Health::class.java) { it.health = 99.0 })
                assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(fixture.action()))
                assertEquals(if (refunded) 1 else 0, fixture.droppedItems.size)
                assertEquals(if (chance in listOf(0.0, 1.0)) 0 else 1, rolls)
                assertEquals(ActionResult.DENIED, fixture.module.actions.execute(fixture.action()))
                assertEquals(if (refunded) 1 else 0, fixture.droppedItems.size)
            }
        }
        PaperTestFixture(dismantleSettings = DismantleSettings(1.0),
            dismantleRandom = { error("Missing metadata must not roll") }).use { fixture ->
            fixture.placeWithItem(null)
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(fixture.action()))
            assertTrue(fixture.droppedItems.isEmpty())
        }
        PaperTestFixture(dismantleSettings = DismantleSettings(0.0)).use { fixture ->
            fixture.placeWithItem(Key("missing", "material"))
            assertTrue(assertNotNull(fixture.module.citadels.findProtecting(fixture.position))
                .editComponent(Health::class.java) { it.health = 99.0 })
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(fixture.action()))
            assertTrue(fixture.droppedItems.isEmpty())
        }
    }

    @Test
    fun `replacement made by a break listener is never removed or refunded using the old handle`() {
        PaperTestFixture(dismantleSettings = DismantleSettings(1.0)).use { fixture ->
            fixture.place()
            val original = assertNotNull(fixture.module.citadels.findProtecting(fixture.position))
            fixture.afterBreakEvent = {
                assertTrue(fixture.manager.removeCitadel(original))
                val replacement = MutableCitadelDraft(fixture.position).apply {
                    addComponent(CitadelKind::class.java, CitadelKind(PaperCitadelTypes.REINFORCED))
                    addComponent(CitadelOwner::class.java, CitadelOwner(fixture.stranger.id))
                    HealthInitializer(7.0).initialize(fixture.context(fixture.stranger), this)
                }
                assertEquals(ActionResult.SUCCESS, fixture.manager.createCitadel(replacement))
            }

            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(fixture.action()))
            val current = assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
            assertFalse(original.isCurrent)
            assertTrue(current.isCurrent)
            assertEquals(fixture.stranger.id, current.getComponent(CitadelOwner::class.java)?.playerId)
            assertEquals(7.0, fixture.health())
            assertTrue(fixture.droppedItems.isEmpty())
        }
    }

    @Test
    fun `dismantling requires a live player in the target world even when an external validator approves`() {
        PaperTestFixture(permissions = false).use { fixture ->
            fixture.place()
            fixture.validate { ActionValidator.Result.Success }
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(DismantleAction(ActionContext.SYSTEM, fixture.position)))
            fixture.installer.online = false
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(fixture.action()))
            fixture.installer.online = true
            fixture.installer.gameMode = GameMode.SPECTATOR
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(fixture.action()))
            fixture.installer.gameMode = GameMode.SURVIVAL
            val otherWorld = paperProxy<World>("other world") { method, _ ->
                if (method.name == "getUID") UUID(99, 1) else unsupported(method)
            }
            fixture.installer.location = Location(otherWorld, 0.0, 64.0, 0.0)
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(fixture.action()))
            assertTrue(fixture.playerBreaks.isEmpty())
            assertNotNull(fixture.module.citadels.findProtecting(fixture.position))
        }
    }

    @Test
    fun `original custom material and owner survive reload and creative placement also refunds`() {
        for (mode in listOf(GameMode.SURVIVAL, GameMode.CREATIVE)) {
            PaperTestFixture(permissions = true, dismantleSettings = DismantleSettings(1.0)).use { fixture ->
                val adapter = TestMaterialAdapter(Key("custom", "original_reinforcement"))
                fixture.materials.register("custom", TestMaterialRegistry(adapter))
                fixture.placementService.registerMaterial(adapter.getKey(), PaperCitadelTypes.REINFORCED)
                fixture.installer.gameMode = mode
                fixture.installer.held = adapter.createItemStack(2)
                fixture.placementService.place(fixture.installer.player, fixture.block(), adapter.getKey())
                assertEquals(if (mode == GameMode.CREATIVE) 2 else 1, fixture.installer.held.amount)
                fixture.manager.unloadChunk(fixture.worldId, 0, 0).join()
                fixture.loadChunk(0, 0)
                val restored = assertNotNull(fixture.module.citadels.findProtecting(fixture.position))
                assertEquals(fixture.installer.id, restored.getComponent(CitadelOwner::class.java)?.playerId)
                assertEquals(adapter.getKey(), restored.getComponent(ReinforcementItem::class.java)?.materialKey)

                assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(fixture.action()))
                val returned = fixture.droppedItems.single().second
                assertEquals(1, returned.amount)
                assertTrue(adapter.isConsists(returned))
            }
        }
    }

    @Test
    fun `linked block dismantling breaks the clicked part and removes the resolved reinforcement`() {
        PaperTestFixture(permissions = true, dismantleSettings = DismantleSettings(1.0)).use { fixture ->
            val (lower, upper) = fixture.door()
            // Existing linked protection is independent of the current solid-block placement filter.
            assertEquals(ActionResult.SUCCESS, fixture.module.place.place(PlaceAction(
                fixture.context(fixture.installer), upper.testPosition(), PaperCitadelTypes.REINFORCED,
                Key("minecraft", "iron_ingot"))))
            val original = assertNotNull(fixture.module.citadels.findProtecting(upper.testPosition()))
            assertEquals(lower.testPosition(), original.position)

            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(fixture.action(position = upper.testPosition())))
            assertSame(upper, fixture.playerBreaks.single().second)
            assertFalse(original.isCurrent)
            assertNull(fixture.manager.findCitadel(fixture.worldId, lower.x, lower.y, lower.z))
            assertEquals(upper.location.add(0.5, 0.5, 0.5), fixture.droppedItems.single().first)
        }
    }

    @Test
    fun `legacy reinforcement has no implicit owner and dismantle lifecycle leaves external validators alone`() {
        PaperTestFixture(permissions = true).use { fixture ->
            fixture.placeExact(fixture.block())
            val action = fixture.action()
            val coordinator = fixture.module.actions.validatorCoordinator
            assertSame(ActionValidator.Result.Neutral, coordinator.validate(action))
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))
            val grant = fixture.validate { ActionValidator.Result.Success }
            fixture.dismantleService.disable()
            fixture.dismantleService.disable()
            assertSame(ActionValidator.Result.Success, coordinator.validate(action))
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))
            fixture.dismantleService.enable()
            fixture.dismantleService.enable()
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(action))
            assertTrue(fixture.droppedItems.isEmpty())
            assertTrue(coordinator.unregisterValidator(grant))
        }
    }

    private fun PaperTestFixture.action(player: TestPlayer = installer, position: CitadelPosition = this.position) =
        DismantleAction(context(player), position)

    private fun PaperTestFixture.placeWithItem(key: Key?) {
        assertEquals(ActionResult.SUCCESS, module.actions.execute(
            PlaceAction(context(installer), position, PaperCitadelTypes.REINFORCED, key)))
    }

    private fun PaperTestFixture.validate(check: (DismantleAction) -> ActionValidator.Result): ActionValidator<DismantleAction> =
        object : ActionValidator<DismantleAction> {
            override val actionClass = DismantleAction::class.java
            override fun validate(action: DismantleAction) = check(action)
        }.also(module.actions.validatorCoordinator::registerValidator)
}
