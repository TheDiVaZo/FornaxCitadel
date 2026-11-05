package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaperGameplayMessagesTest {
    @Test
    fun `placement routing preserves consumption and rejected interaction behavior`() {
        for (channel in CHANNELS) {
            val messages = messages(
                "placement.success" to channel,
                "placement.denied" to channel,
                "placement.invalid-block" to channel
            )
            PaperTestFixture(permissions = true, messages = messages).use { fixture ->
                fixture.installer.held = fixture.reinforcementStack()
                fixture.installer.failIfActionBarDisabled(channel)
                val placed = fixture.interact()

                dispatch(fixture.placementListener, placed, fixture.plugin)

                assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
                assertEquals(1, fixture.installer.held.amount)
                assertEquals(Event.Result.DENY, placed.useInteractedBlock())
                assertEquals(Event.Result.DENY, placed.useItemInHand())
                assertDelivery(fixture.installer, channel, "placement.success")

                dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)

                assertEquals(1, fixture.installer.held.amount)
                assertEquals(100.0, fixture.health())
                assertDelivery(fixture.installer, channel, "placement.denied")

                dispatch(fixture.placementListener, fixture.interact(fixture.block(x = Int.MAX_VALUE)), fixture.plugin)

                assertEquals(1, fixture.installer.held.amount)
                assertDelivery(fixture.installer, channel, "placement.invalid-block")
            }
        }
    }

    @Test
    fun `damage and removal routing preserve health and vanilla block protection`() {
        for (channel in CHANNELS) {
            val messages = messages("breaking.damaged" to channel, "breaking.removed" to channel)
            PaperTestFixture(permissions = true, messages = messages).use { fixture ->
                fixture.place()
                fixture.playerDamage.register(PlayerDamageModifier { _, _, _ -> 50.0 })
                fixture.stranger.failIfActionBarDisabled(channel)

                val hit = fixture.breakBlock()

                assertTrue(hit.isCancelled)
                assertEquals(50.0, fixture.health())
                assertDelivery(fixture.stranger, channel, "breaking.damaged")

                val lethal = fixture.breakBlock()

                assertTrue(lethal.isCancelled)
                assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
                assertEquals(0, fixture.vanillaBlockChanges)
                assertDelivery(fixture.stranger, channel, "breaking.removed")
                assertFalse(fixture.breakBlock().isCancelled)
                assertDelivery(fixture.stranger, "NONE", "")
            }
        }
    }

    @Test
    fun `unavailable data and permission rejection remain protected in every channel`() {
        for (channel in CHANNELS) {
            val messages = messages("breaking.chunk-unavailable" to channel, "breaking.denied" to channel)
            PaperTestFixture(load = false, permissions = true, messages = messages).use { fixture ->
                fixture.stranger.failIfActionBarDisabled(channel)
                val mining = BlockDamageEvent(fixture.stranger.player, fixture.block(), BlockFace.UP,
                    fixture.stranger.held, false)

                dispatch(fixture.breakingListener, mining, fixture.plugin)

                assertTrue(mining.isCancelled)
                assertDelivery(fixture.stranger, channel, "breaking.chunk-unavailable")
                assertTrue(fixture.breakBlock().isCancelled)
                assertDelivery(fixture.stranger, channel, "breaking.chunk-unavailable")

                fixture.manager.loadChunk(fixture.worldId, 0, 0).join()
                fixture.place()
                fixture.stranger.permissions.remove("fornaxcitadel.damage")

                assertTrue(fixture.breakBlock().isCancelled)
                assertEquals(100.0, fixture.health())
                assertEquals(0, fixture.vanillaBlockChanges)
                assertDelivery(fixture.stranger, channel, "breaking.denied")
            }
        }
    }

    @Test
    fun `internal failures use their configured channel without consuming items or damaging blocks`() {
        for (channel in CHANNELS) {
            val messages = messages("placement.error" to channel, "breaking.error" to channel)
            PaperTestFixture(permissions = true, messages = messages).use { fixture ->
                fixture.place()
                fixture.installer.held = fixture.reinforcementStack()
                fixture.installer.failIfActionBarDisabled(channel)
                fixture.stranger.failIfActionBarDisabled(channel)
                fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<PlaceAction> {
                    override val actionClass = PlaceAction::class.java
                    override fun validate(action: PlaceAction): ActionValidator.Result =
                        throw IllegalStateException("Placement validation failed")
                })

                dispatch(fixture.placementListener, fixture.interact(fixture.block(x = 3)), fixture.plugin)

                assertNull(fixture.manager.findCitadel(fixture.worldId, 3, 64, 2))
                assertEquals(2, fixture.installer.held.amount)
                assertDelivery(fixture.installer, channel, "placement.error")

                fixture.playerDamage.register(PlayerDamageModifier { _, _, _ -> Double.NaN })

                assertTrue(fixture.breakBlock().isCancelled)
                assertEquals(100.0, fixture.health())
                assertEquals(0, fixture.vanillaBlockChanges)
                assertDelivery(fixture.stranger, channel, "breaking.error")
            }
        }
    }

    @Test
    fun `each outcome selects its own channel independently`() {
        val messages = messages(
            "placement.success" to "CHAT",
            "placement.denied" to "NONE",
            "breaking.damaged" to "ACTION_BAR",
            "breaking.removed" to "CHAT"
        )
        PaperTestFixture(permissions = true, messages = messages).use { fixture ->
            fixture.installer.held = fixture.reinforcementStack()
            fixture.playerDamage.register(PlayerDamageModifier { _, _, _ -> 50.0 })

            dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
            assertDelivery(fixture.installer, "CHAT", "placement.success")
            dispatch(fixture.placementListener, fixture.interact(), fixture.plugin)
            assertDelivery(fixture.installer, "NONE", "placement.denied")
            fixture.breakBlock()
            assertDelivery(fixture.stranger, "ACTION_BAR", "breaking.damaged")
            fixture.breakBlock()
            assertDelivery(fixture.stranger, "CHAT", "breaking.removed")
        }
    }

    private fun messages(vararg channels: Pair<String, String>): PaperMessages =
        PaperMessages.read(YamlConfiguration().apply {
            for ((key, channel) in channels) {
                set("$key.text", key)
                set("$key.channel", channel)
            }
        })

    private fun PaperTestFixture.interact(block: Block = block()) = PlayerInteractEvent(
        installer.player, Action.RIGHT_CLICK_BLOCK, installer.held, block, BlockFace.UP, EquipmentSlot.HAND
    )

    private fun PaperTestFixture.breakBlock(): BlockBreakEvent =
        BlockBreakEvent(block(), stranger.player).also { dispatch(breakingListener, it, plugin) }

    private fun TestPlayer.failIfActionBarDisabled(channel: String) {
        if (channel == "NONE") actionBarFailure = IllegalStateException("Disabled messages must not be sent")
    }

    private fun assertDelivery(player: TestPlayer, channel: String, text: String) {
        fun List<Any?>.plain() = map { PlainTextComponentSerializer.plainText().serialize(it as Component) }
        val expected = if (channel == "NONE") emptyList() else listOf(text)
        assertEquals(if (channel == "CHAT") expected else emptyList(), player.chatMessages.plain())
        assertEquals(if (channel == "ACTION_BAR") expected else emptyList(), player.actionBarMessages.plain())
        assertEquals(expected, player.messages.plain())
        player.chatMessages.clear()
        player.actionBarMessages.clear()
        player.messages.clear()
    }

    private companion object {
        val CHANNELS = listOf("CHAT", "ACTION_BAR", "NONE")
    }
}
