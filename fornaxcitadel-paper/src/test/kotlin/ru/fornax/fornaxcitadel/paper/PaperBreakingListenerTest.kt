package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.block.BlockFace
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.breaking.BreakAction
import ru.fornax.fornaxcitadel.modules.breaking.BreakDecision
import ru.fornax.fornaxcitadel.modules.breaking.BreakPolicy
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifier
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.modifiers.CreativeModeDamageModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperBreakingListenerTest {
    @Test
    fun `custom removal policy skips mining delay but reinforcement removal preserves physical block`() {
        val fixture = PaperTestFixture(permissions = true, breakPolicy = BreakPolicy { _, _ -> BreakDecision.REMOVE })
        fixture.place()
        val damage = BlockDamageEvent(fixture.installer.player, fixture.block(), BlockFace.UP, fixture.installer.held, false)

        dispatch(fixture.breakingListener, damage, fixture.plugin)

        assertTrue(damage.instaBreak)
        assertEquals(100.0, fixture.health())
        val breaking = BlockBreakEvent(fixture.block(), fixture.installer.player)
        dispatch(fixture.breakingListener, breaking, fixture.plugin)

        assertTrue(breaking.isCancelled)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(0, fixture.vanillaBlockChanges)
        val nextAttempt = BlockBreakEvent(fixture.block(), fixture.installer.player)
        dispatch(fixture.breakingListener, nextAttempt, fixture.plugin)
        assertFalse(nextAttempt.isCancelled)
    }

    @Test
    fun `installer damages reinforcement without skipping mining delay`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.place()
        val damage = BlockDamageEvent(fixture.installer.player, fixture.block(), BlockFace.UP, fixture.installer.held, false)

        dispatch(fixture.breakingListener, damage, fixture.plugin)

        assertFalse(damage.instaBreak)
        assertEquals(100.0, fixture.health())
        val breaking = BlockBreakEvent(fixture.block(), fixture.installer.player)
        dispatch(fixture.breakingListener, breaking, fixture.plugin)

        assertTrue(breaking.isCancelled)
        assertEquals(99.0, fixture.health())
        assertEquals(0, fixture.vanillaBlockChanges)
    }

    @Test
    fun `outsider damages health per completed break and lethal hit only removes reinforcement`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.playerDamage.register(PlayerDamageModifier { _, _, _ -> 25.0 })
        fixture.place()
        val damage = BlockDamageEvent(fixture.stranger.player, fixture.block(), BlockFace.UP, fixture.stranger.held, false)
        dispatch(fixture.breakingListener, damage, fixture.plugin)
        assertFalse(damage.instaBreak)

        repeat(3) { attempt ->
            val breaking = BlockBreakEvent(fixture.block(), fixture.stranger.player)
            dispatch(fixture.breakingListener, breaking, fixture.plugin)
            assertTrue(breaking.isCancelled)
            assertEquals(100.0 - (attempt + 1) * 25.0, fixture.health())
        }
        val lethal = BlockBreakEvent(fixture.block(), fixture.stranger.player)
        dispatch(fixture.breakingListener, lethal, fixture.plugin)
        assertTrue(lethal.isCancelled)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(0, fixture.vanillaBlockChanges)
    }

    @Test
    fun `cancelled Bukkit break never reaches the core action`() {
        val fixture = PaperTestFixture()
        fixture.place()
        val event = BlockBreakEvent(fixture.block(), fixture.stranger.player)
        event.isCancelled = true

        dispatch(fixture.breakingListener, event, fixture.plugin)

        assertEquals(100.0, fixture.health())
    }

    @Test
    fun `break action validators observe vanilla destruction already cancelled`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.place()
        val event = BlockBreakEvent(fixture.block(), fixture.stranger.player)
        var cancelledDuringValidation: Boolean? = null
        fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<BreakAction> {
            override val actionClass = BreakAction::class.java

            override fun validate(action: BreakAction): ActionValidator.Result {
                cancelledDuringValidation = event.isCancelled
                return ActionValidator.Result.Success
            }
        })

        dispatch(fixture.breakingListener, event, fixture.plugin)

        assertEquals(true, cancelledDuringValidation)
        assertTrue(event.isCancelled)
        assertEquals(99.0, fixture.health())
    }

    @Test
    fun `mining and breaking stay cancelled when even error reporting fails`() {
        val fixture = PaperTestFixture(load = false)
        val failure = IllegalStateException("Action bar delivery failed")
        fixture.stranger.actionBarFailure = failure
        val damage = BlockDamageEvent(fixture.stranger.player, fixture.block(), BlockFace.UP, fixture.stranger.held, false)
        val breaking = BlockBreakEvent(fixture.block(), fixture.stranger.player)

        assertSame(failure, assertFailsWith<IllegalStateException> { fixture.breakingListener.onDamage(damage) })
        assertTrue(damage.isCancelled)
        assertSame(failure, assertFailsWith<IllegalStateException> { fixture.breakingListener.onBreak(breaking) })
        assertTrue(breaking.isCancelled)
        assertEquals(0, fixture.vanillaBlockChanges)
    }

    @Test
    fun `installer cannot bypass damage permission rejection`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.place()
        fixture.installer.permissions.remove("fornaxcitadel.damage")

        val event = BlockBreakEvent(fixture.block(), fixture.installer.player)
        dispatch(fixture.breakingListener, event, fixture.plugin)

        assertTrue(event.isCancelled)
        assertEquals(100.0, fixture.health())
        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
            DamageAction(fixture.context(fixture.installer), fixture.position, 100.0)
        ))
        assertEquals(100.0, fixture.health())
    }

    @Test
    fun `unloaded core data fails closed while an ordinary loaded block remains breakable`() {
        val fixture = PaperTestFixture(load = false)
        val unavailable = BlockBreakEvent(fixture.block(), fixture.stranger.player)
        dispatch(fixture.breakingListener, unavailable, fixture.plugin)
        assertTrue(unavailable.isCancelled)

        fixture.manager.loadChunk(fixture.worldId, 0, 0).join()
        val ordinary = BlockBreakEvent(fixture.block(), fixture.stranger.player)
        dispatch(fixture.breakingListener, ordinary, fixture.plugin)
        assertFalse(ordinary.isCancelled)
        assertEquals(0, fixture.vanillaBlockChanges)
    }

    @Test
    fun `invulnerable type rejects both Bukkit breaking and direct damage`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.installer.permissions.add("fornaxcitadel.admin")
        fixture.place(PaperCitadelTypes.INVULNERABLE)
        val event = BlockBreakEvent(fixture.block(), fixture.stranger.player)
        dispatch(fixture.breakingListener, event, fixture.plugin)
        assertTrue(event.isCancelled)
        assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
            DamageAction(fixture.context(fixture.stranger), fixture.position, 100.0)
        ))
        assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
    }

    @Test
    fun `damage is recalculated for each player hit but not previews ordinary blocks or cancelled events`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.place()
        val actors = mutableListOf<java.util.UUID>()
        var installerDamage = 2.0
        fixture.playerDamage.register(PlayerDamageModifier { player, citadel, currentDamage ->
            assertEquals(1.0, currentDamage)
            assertEquals(fixture.position, citadel.position)
            actors.add(player.uniqueId)
            if (player.uniqueId == fixture.installer.id) installerDamage else 3.0
        })
        val preview = BlockDamageEvent(fixture.installer.player, fixture.block(), BlockFace.UP, fixture.installer.held, false)
        val cancelled = BlockBreakEvent(fixture.block(), fixture.stranger.player).apply { isCancelled = true }
        val ordinary = BlockBreakEvent(fixture.block(3, 64, 2), fixture.stranger.player)

        dispatch(fixture.breakingListener, preview, fixture.plugin)
        dispatch(fixture.breakingListener, cancelled, fixture.plugin)
        dispatch(fixture.breakingListener, ordinary, fixture.plugin)

        assertTrue(actors.isEmpty())
        assertFalse(ordinary.isCancelled)
        assertEquals(100.0, fixture.health())
        dispatch(fixture.breakingListener, BlockBreakEvent(fixture.block(), fixture.installer.player), fixture.plugin)
        assertEquals(98.0, fixture.health())
        dispatch(fixture.breakingListener, BlockBreakEvent(fixture.block(), fixture.stranger.player), fixture.plugin)
        assertEquals(95.0, fixture.health())
        installerDamage = 4.0
        dispatch(fixture.breakingListener, BlockBreakEvent(fixture.block(), fixture.installer.player), fixture.plugin)

        assertEquals(91.0, fixture.health())
        assertEquals(listOf(fixture.installer.id, fixture.stranger.id, fixture.installer.id), actors)
        assertEquals(0, fixture.vanillaBlockChanges)
    }

    @Test
    fun `invalid modifier damage preserves the physical block and reinforcement health`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.place()
        fixture.playerDamage.register(PlayerDamageModifier { _, _, _ -> Double.NaN })
        val event = BlockBreakEvent(fixture.block(), fixture.installer.player)

        dispatch(fixture.breakingListener, event, fixture.plugin)

        assertTrue(event.isCancelled)
        assertEquals(100.0, fixture.health())
        assertEquals(0, fixture.vanillaBlockChanges)
    }

    @Test
    fun `creative lethal damage still respects permissions and invulnerable definitions`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.playerDamage.register(CreativeModeDamageModifier())
        fixture.installer.gameMode = GameMode.CREATIVE
        fixture.place()
        fixture.installer.permissions.remove("fornaxcitadel.damage")
        val denied = BlockBreakEvent(fixture.block(), fixture.installer.player)

        dispatch(fixture.breakingListener, denied, fixture.plugin)

        assertTrue(denied.isCancelled)
        assertEquals(100.0, fixture.health())
        fixture.installer.permissions.add("fornaxcitadel.damage")
        val allowed = BlockBreakEvent(fixture.block(), fixture.installer.player)
        dispatch(fixture.breakingListener, allowed, fixture.plugin)
        assertTrue(allowed.isCancelled)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))

        fixture.installer.permissions.add("fornaxcitadel.admin")
        fixture.place(PaperCitadelTypes.INVULNERABLE)
        val invulnerable = BlockBreakEvent(fixture.block(), fixture.installer.player)
        dispatch(fixture.breakingListener, invulnerable, fixture.plugin)
        assertTrue(invulnerable.isCancelled)
        assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(0, fixture.vanillaBlockChanges)
    }
}
