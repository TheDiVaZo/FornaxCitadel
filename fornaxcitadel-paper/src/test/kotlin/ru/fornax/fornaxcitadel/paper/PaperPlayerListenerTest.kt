package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaperPlayerListenerTest {
    @Test
    fun `owner skips mining delay but reinforcement removal preserves physical block`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.place()
        val damage = BlockDamageEvent(fixture.owner.player, fixture.block(), BlockFace.UP, fixture.owner.held, false)

        dispatch(fixture.listener, damage, fixture.plugin)

        assertTrue(damage.instaBreak)
        assertEquals(100.0, fixture.health())
        val breaking = BlockBreakEvent(fixture.block(), fixture.owner.player)
        dispatch(fixture.listener, breaking, fixture.plugin)

        assertTrue(breaking.isCancelled)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(0, fixture.vanillaBlockChanges)
        val nextAttempt = BlockBreakEvent(fixture.block(), fixture.owner.player)
        dispatch(fixture.listener, nextAttempt, fixture.plugin)
        assertFalse(nextAttempt.isCancelled)
    }

    @Test
    fun `outsider damages health per completed break and lethal hit only removes reinforcement`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.place()
        val damage = BlockDamageEvent(fixture.stranger.player, fixture.block(), BlockFace.UP, fixture.stranger.held, false)
        dispatch(fixture.listener, damage, fixture.plugin)
        assertFalse(damage.instaBreak)

        repeat(3) { attempt ->
            val breaking = BlockBreakEvent(fixture.block(), fixture.stranger.player)
            dispatch(fixture.listener, breaking, fixture.plugin)
            assertTrue(breaking.isCancelled)
            assertEquals(100.0 - (attempt + 1) * 25.0, fixture.health())
        }
        val lethal = BlockBreakEvent(fixture.block(), fixture.stranger.player)
        dispatch(fixture.listener, lethal, fixture.plugin)
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

        dispatch(fixture.listener, event, fixture.plugin)

        assertEquals(100.0, fixture.health())
    }

    @Test
    fun `owner mining shortcut does not bypass permission rejection`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.place()
        fixture.owner.permissions.remove("fornaxcitadel.damage")

        val event = BlockBreakEvent(fixture.block(), fixture.owner.player)
        dispatch(fixture.listener, event, fixture.plugin)

        assertTrue(event.isCancelled)
        assertEquals(100.0, fixture.health())
        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
            DamageAction(fixture.context(fixture.owner), fixture.position, 100.0)
        ))
        assertEquals(100.0, fixture.health())
    }

    @Test
    fun `unloaded core data fails closed while an ordinary loaded block remains breakable`() {
        val fixture = PaperTestFixture(load = false)
        val unavailable = BlockBreakEvent(fixture.block(), fixture.stranger.player)
        dispatch(fixture.listener, unavailable, fixture.plugin)
        assertTrue(unavailable.isCancelled)

        fixture.manager.loadChunk(fixture.worldId, 0, 0).join()
        val ordinary = BlockBreakEvent(fixture.block(), fixture.stranger.player)
        dispatch(fixture.listener, ordinary, fixture.plugin)
        assertFalse(ordinary.isCancelled)
        assertEquals(0, fixture.vanillaBlockChanges)
    }

    @Test
    fun `marked main hand placement consumes once and duplicate placement consumes nothing`() {
        val fixture = PaperTestFixture(permissions = true)
        val held = fixture.markedStack(amount = 2)
        fixture.owner.held = held
        val placed = fixture.interact()

        dispatch(fixture.listener, placed, fixture.plugin)

        assertEquals(1, held.amount)
        assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(Event.Result.DENY, placed.useInteractedBlock())
        assertEquals(Event.Result.DENY, placed.useItemInHand())
        dispatch(fixture.listener, fixture.interact(), fixture.plugin)
        assertEquals(1, held.amount)
    }

    @Test
    fun `off hand unmarked and explicitly denied item interactions do not place`() {
        val fixture = PaperTestFixture()
        fixture.owner.held = fixture.markedStack()
        dispatch(fixture.listener, fixture.interact(EquipmentSlot.OFF_HAND), fixture.plugin)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.owner.held.amount)

        val denied = fixture.interact().apply { setUseItemInHand(Event.Result.DENY) }
        dispatch(fixture.listener, denied, fixture.plugin)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.owner.held.amount)

        fixture.owner.held = TestItemStack(Material.AMETHYST_SHARD, 2)
        dispatch(fixture.listener, fixture.interact(), fixture.plugin)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.owner.held.amount)
    }

    @Test
    fun `vanilla block no-op permits marked item while creative placement keeps its stack`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.owner.held = fixture.markedStack()
        fixture.owner.gameMode = GameMode.CREATIVE
        val event = fixture.interact().apply { setUseInteractedBlock(Event.Result.DENY) }

        dispatch(fixture.listener, event, fixture.plugin)

        assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.owner.held.amount)
    }

    @Test
    fun `placement permission and admin type rejection preserve the item stack`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.owner.held = fixture.markedStack()
        fixture.owner.permissions.remove("fornaxcitadel.place")
        dispatch(fixture.listener, fixture.interact(), fixture.plugin)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.owner.held.amount)

        fixture.owner.permissions.add("fornaxcitadel.place")
        fixture.owner.held = fixture.markedStack(PaperCitadelTypes.INVULNERABLE)
        dispatch(fixture.listener, fixture.interact(), fixture.plugin)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(2, fixture.owner.held.amount)
    }

    @Test
    fun `invulnerable type rejects both Bukkit breaking and direct damage`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.owner.permissions.add("fornaxcitadel.admin")
        fixture.place(PaperCitadelTypes.INVULNERABLE)
        val event = BlockBreakEvent(fixture.block(), fixture.stranger.player)
        dispatch(fixture.listener, event, fixture.plugin)
        assertTrue(event.isCancelled)
        assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
            DamageAction(fixture.context(fixture.stranger), fixture.position, 100.0)
        ))
        assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
    }

    private fun PaperTestFixture.interact(hand: EquipmentSlot = EquipmentSlot.HAND) = PlayerInteractEvent(
        owner.player, Action.RIGHT_CLICK_BLOCK, owner.held, block(), BlockFace.UP, hand
    )
}

/** Uses Bukkit's own cancellation dispatch instead of bypassing EventHandler semantics. */
internal fun dispatch(listener: Listener, event: Event, plugin: Plugin) {
    val method = listener.javaClass.methods.single {
        it.isAnnotationPresent(EventHandler::class.java) && it.parameterTypes.contentEquals(arrayOf(event.javaClass))
    }
    val handler = method.getAnnotation(EventHandler::class.java)
    RegisteredListener(listener, EventExecutor { _, received -> method.invoke(listener, received) },
        handler.priority, plugin, handler.ignoreCancelled).callEvent(event)
}
