package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleListener
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.DamagePolicy
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.SimpleHealthBehavior
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifier
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.scheduler.PaperScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaperCreativeBreakingTest {
    @Test
    fun `creative removes every reinforcement in one hit without admin or damage modifiers and preserves the block`() {
        val protectedHealth = Key("test", "protected_health")
        for (type in listOf(PaperCitadelTypes.REINFORCED, PaperCitadelTypes.MATURING,
            PaperCitadelTypes.INVULNERABLE, protectedHealth)) {
            PaperTestFixture(permissions = true).use { fixture ->
                fixture.module.registerDefinition(CitadelDefinition(protectedHealth, emptyList()),
                    DamagePolicy.DENY, SimpleHealthBehavior(100.0))
                fixture.installer.permissions.add("fornaxcitadel.admin")
                fixture.place(type)
                val target = assertNotNull(fixture.target())
                assertEquals(type != PaperCitadelTypes.INVULNERABLE, target.getComponent(Health::class.java) != null)
                fixture.manager.saveAll().join()
                fixture.stranger.gameMode = GameMode.CREATIVE
                assertFalse("fornaxcitadel.admin" in fixture.stranger.permissions)
                fixture.playerDamage.register(PlayerDamageModifier { _, _, _ ->
                    error("Creative destruction must not calculate damage")
                })
                val removals = mutableListOf<Pair<Int, RemovalCause>>()
                fixture.module.lifecycle.addListener(object : CitadelLifecycleListener {
                    override fun onRemoved(position: CitadelPosition, runtimeId: Int, cause: RemovalCause) {
                        assertEquals(fixture.position, position)
                        removals.add(runtimeId to cause)
                    }
                })

                val first = fixture.breakAsCreative()

                assertTrue(first.isCancelled, type.asString())
                assertNull(fixture.target(), type.asString())
                assertFalse(target.isCurrent)
                assertEquals(listOf(target.runtimeId to RemovalCause.DESTROYED), removals)
                assertEquals(Material.STONE, fixture.block().type)
                assertEquals(0, fixture.vanillaBlockChanges)
                val next = fixture.breakAsCreative()
                assertFalse(next.isCancelled)
                assertEquals(1, removals.size)

                fixture.manager.unloadChunk(fixture.worldId, 0, 0).join()
                fixture.manager.loadChunk(fixture.worldId, 0, 0).join()
                assertNull(fixture.target(), "Removed reinforcement must not return after reload: $type")
            }
        }
    }

    @Test
    fun `creative destruction requires damage permission even for administrators without the permission service`() {
        for (permissions in listOf(false, true)) {
            PaperTestFixture(permissions = permissions).use { fixture ->
                fixture.prepareInvulnerable()
                fixture.stranger.permissions.add("fornaxcitadel.admin")
                fixture.stranger.permissions.remove("fornaxcitadel.damage")

                assertTrue(fixture.breakAsCreative().isCancelled)
                assertNotNull(fixture.target())

                fixture.stranger.permissions.add("fornaxcitadel.damage")
                assertTrue(fixture.breakAsCreative().isCancelled)
                assertNull(fixture.target())
                assertEquals(0, fixture.vanillaBlockChanges)
            }
        }
    }

    @Test
    fun `disabled creative option uses ordinary modifiers and keeps invulnerable reinforcement protected`() {
        for (type in listOf(PaperCitadelTypes.REINFORCED, PaperCitadelTypes.INVULNERABLE)) {
            PaperTestFixture(permissions = true, creativeModeEnabled = false).use { fixture ->
                fixture.installer.permissions.add("fornaxcitadel.admin")
                fixture.place(type)
                fixture.stranger.gameMode = GameMode.CREATIVE
                var modifierCalls = 0
                fixture.playerDamage.register(PlayerDamageModifier { _, _, _ -> modifierCalls++; 3.0 })

                assertTrue(fixture.breakAsCreative().isCancelled)

                assertEquals(1, modifierCalls)
                assertNotNull(fixture.target())
                if (type == PaperCitadelTypes.REINFORCED) assertEquals(97.0, fixture.health())
                assertEquals(ActionResult.DENIED, fixture.module.actions.execute(fixture.destroyAction()))
                assertNotNull(fixture.target())
                assertEquals(0, fixture.vanillaBlockChanges)
            }
        }
    }

    @Test
    fun `damage and base action validators can reject creative destruction while the block is already protected`() {
        assertExternalRejection(DamageAction::class.java)
        assertExternalRejection(ActionData::class.java)
    }

    @Test
    fun `cancelled breaks and unavailable world or core chunks do not remove creative targets`() {
        PaperTestFixture(permissions = true).use { fixture ->
            fixture.prepareInvulnerable()
            var actionCalls = 0
            fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction): ActionValidator.Result {
                    actionCalls++
                    return ActionValidator.Result.Success
                }
            })
            val cancelled = BlockBreakEvent(fixture.block(), fixture.stranger.player).apply { isCancelled = true }

            dispatch(fixture.breakingListener, cancelled, fixture.plugin)

            assertTrue(cancelled.isCancelled)
            assertEquals(0, actionCalls)
            assertNotNull(fixture.target())
            fixture.worldChunkLoaded = false
            assertTrue(fixture.breakAsCreative().isCancelled)
            assertNotNull(fixture.target())
            fixture.worldChunkLoaded = true
            fixture.manager.unloadChunk(fixture.worldId, 0, 0).join()
            assertTrue(fixture.breakAsCreative().isCancelled)
            assertEquals(0, actionCalls)
            fixture.manager.loadChunk(fixture.worldId, 0, 0).join()
            assertNotNull(fixture.target())
            assertEquals(0, fixture.vanillaBlockChanges)
        }
    }

    @Test
    fun `queued creative destruction rechecks live mode presence and damage permission`() {
        val revoke = listOf<Pair<String, (PaperTestFixture) -> Unit>>(
            "survival" to { it.stranger.gameMode = GameMode.SURVIVAL },
            "adventure" to { it.stranger.gameMode = GameMode.ADVENTURE },
            "spectator" to { it.stranger.gameMode = GameMode.SPECTATOR },
            "offline" to { it.stranger.online = false },
            "missing player" to { it.players.remove(it.stranger.id) },
            "permission revoked" to { it.stranger.permissions.remove("fornaxcitadel.damage") }
        )
        for ((reason, change) in revoke) {
            PaperTestFixture(permissions = true, moduleSchedulerFactory = ::PaperScheduler).use { fixture ->
                fixture.prepareInvulnerable()
                fixture.module.actions.post(fixture.destroyAction())
                change(fixture)

                fixture.runScheduledTasks()

                assertNotNull(fixture.target(), reason)
                fixture.players[fixture.stranger.id] = fixture.stranger
                fixture.stranger.online = true
                fixture.stranger.gameMode = GameMode.CREATIVE
                fixture.stranger.permissions.add("fornaxcitadel.damage")
                fixture.module.actions.post(fixture.destroyAction())
                fixture.runScheduledTasks()
                assertNull(fixture.target(), reason)
                assertEquals(0, fixture.vanillaBlockChanges)
            }
        }
    }

    @Test
    fun `direct destruction without a creative actor is denied even without platform validation`() {
        PaperTestFixture().use { fixture ->
            fixture.prepareInvulnerable()
            for (mode in listOf(GameMode.SURVIVAL, GameMode.ADVENTURE, GameMode.SPECTATOR)) {
                fixture.stranger.gameMode = mode
                assertEquals(ActionResult.DENIED, fixture.module.actions.execute(fixture.destroyAction()), mode.name)
            }
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
                DamageAction(ActionContext.SYSTEM, fixture.position, Double.MAX_VALUE, DamageAction.Mode.DESTROY)
            ))
            fixture.stranger.gameMode = GameMode.CREATIVE
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
                DamageAction(fixture.context(fixture.stranger), fixture.position, Double.MAX_VALUE)
            ))
            assertNotNull(fixture.target())
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(fixture.destroyAction()))
            assertNull(fixture.target())
        }
    }

    private fun <T : ActionData> assertExternalRejection(acceptedClass: Class<T>) {
        PaperTestFixture(permissions = true).use { fixture ->
            fixture.prepareInvulnerable()
            val event = BlockBreakEvent(fixture.block(), fixture.stranger.player)
            var validationCalls = 0
            fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<T> {
                override val actionClass = acceptedClass
                override fun validate(action: T): ActionValidator.Result {
                    validationCalls++
                    assertTrue(event.isCancelled)
                    assertEquals(DamageAction.Mode.DESTROY, (action as DamageAction).mode)
                    return ActionValidator.Result.Rejected
                }
            })

            dispatch(fixture.breakingListener, event, fixture.plugin)

            assertEquals(1, validationCalls, acceptedClass.simpleName)
            assertTrue(event.isCancelled)
            assertNotNull(fixture.target())
            assertEquals(0, fixture.vanillaBlockChanges)
        }
    }

    private fun PaperTestFixture.prepareInvulnerable() {
        installer.permissions.add("fornaxcitadel.admin")
        place(PaperCitadelTypes.INVULNERABLE)
        stranger.gameMode = GameMode.CREATIVE
    }

    private fun PaperTestFixture.breakAsCreative(): BlockBreakEvent =
        BlockBreakEvent(block(), stranger.player).also { dispatch(breakingListener, it, plugin) }

    private fun PaperTestFixture.destroyAction() =
        DamageAction(context(stranger), position, Double.MAX_VALUE, DamageAction.Mode.DESTROY)

    private fun PaperTestFixture.target(): CitadelHandle? =
        manager.findCitadel(worldId, position.x, position.y, position.z)
}
