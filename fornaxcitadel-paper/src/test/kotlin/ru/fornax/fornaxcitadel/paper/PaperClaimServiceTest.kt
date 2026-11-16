package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.block.BlockFace
import org.bukkit.inventory.EquipmentSlot
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.systems.action.*
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.claim.ClaimAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import kotlin.test.*

class PaperClaimServiceTest {
    @Test
    fun `standalone claim changes only owner and authorization is read only without an item requirement`() {
        PaperTestFixture(permissions = true).use { f ->
            f.placeUnowned()
            val target = f.citadel()
            val health = target.getComponent(Health::class.java)
            val maturing = target.getComponent(Maturing::class.java)
            val kind = target.getComponent(CitadelKind::class.java)
            f.installer.held = TestItemStack(Material.AIR, 0)
            val notifications = mutableListOf<ActionData>()
            f.observe(notifications::add)
            val claim = ClaimAction(f.context(f.installer), f.position)
            f.manager.saveAll().join()
            val saves = f.backend.saves
            assertTrue(f.module.actions.isAllowed(claim))
            assertNull(target.getComponent(CitadelOwner::class.java))
            f.manager.saveAll().join()
            assertEquals(saves, f.backend.saves)
            assertTrue(notifications.isEmpty())

            assertEquals(ActionResult.SUCCESS, f.module.actions.execute(claim))
            assertEquals(listOf<ActionData>(claim), notifications)
            assertEquals(f.installer.id, target.getComponent(CitadelOwner::class.java)?.playerId)
            assertSame(health, target.getComponent(Health::class.java))
            assertSame(maturing, target.getComponent(Maturing::class.java))
            assertSame(kind, target.getComponent(CitadelKind::class.java))
            assertEquals(Material.AIR, f.installer.held.type)
            assertTrue(f.droppedItems.isEmpty())
            assertEquals(ActionResult.DENIED, f.module.actions.execute(claim))
            assertEquals(listOf<ActionData>(claim), notifications)
        }
    }

    @Test
    fun `claim cannot bypass player state permissions ownership or availability even with external approval`() {
        for (reason in listOf("permission", "offline", "spectator", "world", "chunk", "storage", "block", "thread", "system", "owned", "self")) {
            PaperTestFixture(permissions = true).use { f ->
                f.placeUnowned()
                val target = f.citadel()
                f.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<ClaimAction> {
                    override val actionClass = ClaimAction::class.java
                    override fun validate(action: ClaimAction) = ActionValidator.Result.Success
                })
                when (reason) {
                    "permission" -> f.installer.permissions.remove("fornaxcitadel.repair")
                    "offline" -> f.installer.online = false
                    "spectator" -> f.installer.gameMode = GameMode.SPECTATOR
                    "world" -> f.worldAvailable = false
                    "chunk" -> f.worldChunkLoaded = false
                    "storage" -> f.manager.unloadChunk(f.worldId, 0, 0).join()
                    "block" -> f.material(Material.AIR)
                    "thread" -> f.mainThread = false
                    "owned", "self" -> f.manager.putComponent(CitadelOwner::class.java, f.worldId, 1, 64, 2,
                        CitadelOwner(if (reason == "self") f.installer.id else f.stranger.id))
                }
                val originalOwner = target.getComponent(CitadelOwner::class.java)?.playerId
                val context = if (reason == "system") ActionContext.SYSTEM else f.context(f.installer)
                assertEquals(ActionResult.DENIED, f.module.actions.execute(ClaimAction(context, f.position)), reason)
                assertEquals(originalOwner, target.getComponent(CitadelOwner::class.java)?.playerId, reason)
                f.worldAvailable = true
                f.worldChunkLoaded = true
                f.mainThread = true
                if (reason == "storage") f.loadChunk(0, 0)
            }
        }
    }

    @Test
    fun `claim veto or disabled claim service prevents every repair mutation`() {
        for (upgrade in listOf(false)) for (disabled in listOf(false, true)) {
            PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings()).use { f ->
                f.placeUnowned()
                val target = f.citadel()
                target.editComponent(Health::class.java) { it.health = 5.0 }
                val startedAt = target.getComponent(Maturing::class.java)?.startedAtMillis
                val kind = target.getComponent(CitadelKind::class.java)?.definitionKey
                val type = PaperCitadelTypes.REINFORCED
                f.installer.held = f.reinforcementStack(type)
                var claims = 0
                f.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<ClaimAction> {
                    override val actionClass = ClaimAction::class.java
                    override fun validate(action: ClaimAction): ActionValidator.Result {
                        claims++
                        assertNull(target.getComponent(CitadelOwner::class.java))
                        assertEquals(5.0, f.health())
                        assertEquals(kind, target.getComponent(CitadelKind::class.java)?.definitionKey)
                        return if (disabled) ActionValidator.Result.Success else ActionValidator.Result.Rejected
                    }
                })
                if (disabled) f.claimService.disable()
                val notifications = mutableListOf<ActionData>()
                f.observe(notifications::add)
                val action = RepairAction(f.context(f.installer), f.position)
                f.manager.saveAll().join()
                val saves = f.backend.saves
                assertEquals(ActionResult.DENIED, f.module.actions.execute(action))
                assertEquals(1, claims)
                assertNull(target.getComponent(CitadelOwner::class.java))
                assertEquals(5.0, f.health())
                assertEquals(kind, target.getComponent(CitadelKind::class.java)?.definitionKey)
                assertEquals(startedAt, target.getComponent(Maturing::class.java)?.startedAtMillis)
                assertEquals(2, f.installer.held.amount)
                assertTrue(f.droppedItems.isEmpty())
                assertTrue(notifications.isEmpty())
                f.manager.saveAll().join()
                assertEquals(saves, f.backend.saves)
            }
        }
    }

    @Test
    fun `posted repairs notify claim then parent only after all changes complete`() {
        for (upgrade in listOf(false)) {
            PaperTestFixture(permissions = true, citadelSettings = CitadelTypesSettings()).use { f ->
                f.placeUnowned()
                f.citadel().editComponent(Health::class.java) { it.health = 5.0 }
                val type = PaperCitadelTypes.REINFORCED
                f.installer.held = f.reinforcementStack(type)
                val expectedHealth = checkNotNull(f.module.health.findDefinition(type)).maxHealth
                val notifications = mutableListOf<Key>()
                f.observe {
                    notifications.add(it.type)
                    assertEquals(f.installer.id, f.citadel().getComponent(CitadelOwner::class.java)?.playerId)
                    assertEquals(expectedHealth, f.health())
                    assertEquals(type, f.citadel().getComponent(CitadelKind::class.java)?.definitionKey)
                    assertEquals(1, f.installer.held.amount)
                }
                val action = RepairAction(f.context(f.installer), f.position)
                f.module.actions.post(action)
                assertNull(f.citadel().getComponent(CitadelOwner::class.java))
                assertEquals(1, f.module.actions.drainPosted(1))
                assertEquals(listOf(ClaimAction.KEY, action.type), notifications)
                assertEquals(0, f.module.actions.drainPosted())
            }
        }
    }

    @Test
    fun `failed parent checks never run claim validators and owned repair skips claim entirely`() {
        for (reason in listOf("wrong item", "full", "parent veto", "owned")) PaperTestFixture(permissions = true).use { f ->
            f.placeUnowned()
            if (reason != "full") f.citadel().editComponent(Health::class.java) { it.health = 5.0 }
            if (reason == "owned") f.manager.putComponent(CitadelOwner::class.java, f.worldId, 1, 64, 2, CitadelOwner(f.installer.id))
            f.installer.held = if (reason == "wrong item") TestItemStack(Material.DIRT, 2) else f.reinforcementStack()
            f.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<ClaimAction> {
                override val actionClass = ClaimAction::class.java
                override fun validate(action: ClaimAction): ActionValidator.Result = error("Unexpected claim: $reason")
            })
            if (reason == "parent veto") f.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<RepairAction> {
                override val actionClass = RepairAction::class.java
                override fun validate(action: RepairAction) = ActionValidator.Result.Rejected
            })
            assertEquals(if (reason == "owned") ActionResult.SUCCESS else ActionResult.DENIED,
                f.module.actions.execute(RepairAction(f.context(f.installer), f.position)), reason)
            assertEquals(if (reason == "owned") f.installer.id else null, f.citadel().getComponent(CitadelOwner::class.java)?.playerId)
        }
    }

    @Test
    fun `claim service lifecycle preserves external validators and never duplicates notifications`() {
        PaperTestFixture(permissions = true).use { f ->
            f.placeUnowned()
            val external = object : ActionValidator<ClaimAction> {
                override val actionClass = ClaimAction::class.java
                override fun validate(action: ClaimAction) = ActionValidator.Result.Success
            }
            f.module.actions.validatorCoordinator.registerValidator(external)
            val claim = ClaimAction(f.context(f.installer), f.position)
            val notifications = mutableListOf<ActionData>()
            f.observe(notifications::add)
            repeat(2) { f.claimService.disable() }
            assertEquals(ActionValidator.Result.Success, f.module.actions.validatorCoordinator.validate(claim))
            assertEquals(ActionResult.DENIED, f.module.actions.execute(claim))
            repeat(2) { f.claimService.enable() }
            assertEquals(ActionResult.SUCCESS, f.module.actions.execute(claim))
            assertEquals(listOf<ActionData>(claim), notifications)
            assertTrue(f.module.actions.validatorCoordinator.unregisterValidator(external))
        }
    }

    @Test
    fun `standalone click uses claim messages and configurable channels for success denial and errors`() {
        for (result in listOf("success", "denied", "error")) {
            val messages = PaperMessages.read(YamlConfiguration().apply { set("claim.$result.channel", "CHAT") })
            PaperTestFixture(permissions = true, messages = messages).use { f ->
                f.placeUnowned()
                f.installer.held = f.reinforcementStack()
                f.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<ClaimAction> {
                    override val actionClass = ClaimAction::class.java
                    override fun validate(action: ClaimAction) = when (result) {
                        "error" -> error("claim validation failed")
                        "denied" -> ActionValidator.Result.Rejected
                        else -> ActionValidator.Result.Success
                    }
                })
                val click = PlayerInteractEvent(f.installer.player, Action.RIGHT_CLICK_BLOCK, f.installer.held,
                    f.block(), BlockFace.UP, EquipmentSlot.HAND)
                dispatch(f.placementListener, click, f.plugin)
                assertEquals<Any?>(messages.render("claim.$result"), f.installer.messages.single())
                assertTrue(f.installer.actionBarMessages.isEmpty())
                assertEquals(result == "success", f.citadel().getComponent(CitadelOwner::class.java) != null)
                assertEquals(2, f.installer.held.amount)
            }
        }
    }

    private fun PaperTestFixture.placeUnowned() {
        place()
        manager.removeComponent(CitadelOwner::class.java, worldId, 1, 64, 2)
    }
    private fun PaperTestFixture.citadel() = assertNotNull(module.citadels.findProtecting(position))
    private fun PaperTestFixture.observe(callback: (ActionData) -> Unit) {
        module.actions.handlerCoordinator.registerHandler(object : ActionHandler<ActionData> {
            override val actionClass = ActionData::class.java
            override fun handle(action: ActionData) = callback(action)
        })
    }
    private companion object { val HEAVY = Key("fornaxcitadel", "reinforced_heavy") }
}
