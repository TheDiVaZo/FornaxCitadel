package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.block.BlockFace
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.event.block.Action as BukkitAction
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.command.CitadelCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerSourceTest {
    @Test
    fun `ordinary and creative breaking retain the initiating player and break source key`() {
        for (gameMode in listOf(GameMode.SURVIVAL, GameMode.CREATIVE)) {
            PaperTestFixture(permissions = true).use { fixture ->
                fixture.place()
                fixture.stranger.gameMode = gameMode
                val observed = fixture.captureActions()
                val event = BlockBreakEvent(fixture.block(), fixture.stranger.player)

                dispatch(fixture.breakingListener, event, fixture.plugin)

                val action = assertIs<DamageAction>(observed.single())
                assertPlayerSource(action, fixture.stranger, Key("paper", "player_break"))
                assertEquals(fixture.position, action.position)
                assertEquals(
                    if (gameMode == GameMode.CREATIVE) DamageAction.Mode.DESTROY else DamageAction.Mode.DAMAGE,
                    action.mode
                )
                assertTrue(event.isCancelled)
                if (gameMode == GameMode.CREATIVE) {
                    assertNull(fixture.citadels.findProtecting(fixture.position))
                } else {
                    assertEquals(99.0, fixture.health())
                }
            }
        }
    }

    @Test
    fun `item placement retains the initiating player and item source key`() {
        PaperTestFixture(permissions = true).use { fixture ->
            val defaultSource = PlayerSource(fixture.installer.player)
            assertSame(fixture.installer.player, defaultSource.player)
            assertEquals(Key("paper", "player"), defaultSource.key)
            fixture.installer.held = fixture.reinforcementStack(amount = 2)
            val observed = fixture.captureActions()
            val event = PlayerInteractEvent(
                fixture.installer.player, BukkitAction.RIGHT_CLICK_BLOCK, fixture.installer.held,
                fixture.block(), BlockFace.UP, EquipmentSlot.HAND
            )

            dispatch(fixture.placementListener, event, fixture.plugin)

            val action = assertIs<PlaceAction>(observed.single())
            assertPlayerSource(action, fixture.installer, Key("paper", "reinforcement_item"))
            assertEquals(fixture.position, action.position)
            assertNotNull(fixture.citadels.findProtecting(fixture.position))
            assertEquals(1, fixture.installer.held.amount)
        }
    }

    @Test
    fun `place and remove commands retain the initiating player and command source key`() {
        PaperTestFixture(permissions = true).use { fixture ->
            fixture.installer.permissions.add("fornaxcitadel.admin")
            fixture.installer.target = fixture.block()
            val observed = fixture.captureActions()
            val commands = CitadelCommand(
                fixture.plugin, fixture.actionFacade, fixture.citadels, fixture.coreGameplay.health,
                fixture.materials, fixture.citadelTypes
            ) {}

            assertTrue(commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("place", "reinforced")))
            assertNotNull(fixture.citadels.findProtecting(fixture.position))
            assertTrue(commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("remove")))

            assertEquals(2, observed.size)
            assertIs<PlaceAction>(observed[0])
            assertIs<RemoveAction>(observed[1])
            for (action in observed) {
                assertPlayerSource(action, fixture.installer, Key("fornaxcitadel", "paper_command"))
                assertEquals(fixture.position, action.position)
            }
            assertNull(fixture.citadels.findProtecting(fixture.position))
        }
    }

    private fun PaperTestFixture.captureActions(): List<ru.fornax.fornaxcitadel.core.systems.action.Action> {
        val observed = mutableListOf<ru.fornax.fornaxcitadel.core.systems.action.Action>()
        actions.registerValidator(object : ActionValidator<ru.fornax.fornaxcitadel.core.systems.action.Action> {
            override val actionClass = Action::class.java
            override fun validate(action: ru.fornax.fornaxcitadel.core.systems.action.Action): ActionValidator.Result {
                observed.add(action)
                return ActionValidator.Result.Neutral
            }
        })
        return observed
    }

    private fun assertPlayerSource(action: ru.fornax.fornaxcitadel.core.systems.action.Action, player: TestPlayer, key: Key) {
        val source = assertIs<PlayerSource>(action.context.source)
        assertSame(player.player, source.player)
        assertEquals(player.id, action.context.actorId)
        assertEquals(key, source.key)
    }

    private object TestCommand : Command("citadel") {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean =
            error("Only the registered command executor is exercised")
    }
}
