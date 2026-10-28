package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.breaking.BreakAction
import ru.fornax.fornaxcitadel.modules.breaking.BreakDecision
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import java.util.logging.Level

/** Translates player interactions to core actions on the server's main thread. */
class PaperPlayerListener(
    private val plugin: Plugin,
    private val manager: SimpleComponentManager,
    private val module: CitadelModule,
    private val items: CitadelItems,
    private val damage: Double
) : Listener {
    init {
        require(damage.isFinite() && damage > 0.0) { "Break damage must be finite and positive" }
    }

    // A vanilla no-op may deny block interaction while still allowing use of a custom item.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        if (event.useItemInHand() == Event.Result.DENY) return
        val type = items.typeOf(event.item) ?: return
        val block = event.clickedBlock ?: return
        val held = event.player.inventory.itemInMainHand
        if (items.typeOf(held) != type) return

        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        if (!block.supportsCitadelData()) {
            deny(event.player, "В этой точке нельзя установить укрепление.")
            return
        }

        try {
            val result = module.actions.execute(PlaceAction(
                ActionContext(event.player.uniqueId, ITEM_SOURCE),
                CitadelPosition(block.world.uid, block.x, block.y, block.z),
                type
            ))
            if (result != ActionResult.SUCCESS) {
                deny(event.player, "Не удалось установить укрепление.")
                return
            }
            if (event.player.gameMode != GameMode.CREATIVE) {
                held.amount--
                event.player.inventory.setItemInMainHand(if (held.amount == 0) null else held)
            }
            event.player.sendActionBar(Component.text("Укрепление установлено.", NamedTextColor.GREEN))
        } catch (exception: Exception) {
            report(event.player, "Could not place a citadel", exception)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: BlockDamageEvent) {
        val block = event.block
        if (!block.supportsCitadelData()) return
        try {
            if (!manager.isChunkLoaded(block.world.uid, block.x shr 4, block.z shr 4)) {
                event.isCancelled = true
                deny(event.player, "Данные чанка пока недоступны.")
                return
            }
            val citadel = manager.findCitadel(block.world.uid, block.x, block.y, block.z) ?: return
            val kind = citadel.getComponent(CitadelKind::class.java) ?: return
            val policy = module.breaking.findPolicy(kind.definitionKey) ?: return
            val context = ActionContext(event.player.uniqueId, BREAK_SOURCE)
            if (policy.decide(context, citadel) == BreakDecision.REMOVE) {
                // This only skips the vanilla mining delay. onBreak still authorizes the action.
                event.instaBreak = true
            }
        } catch (exception: Exception) {
            event.isCancelled = true
            report(event.player, "Could not check a citadel breaking attempt", exception)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        if (!block.supportsCitadelData()) return
        try {
            if (!manager.isChunkLoaded(block.world.uid, block.x shr 4, block.z shr 4)) {
                event.isCancelled = true
                deny(event.player, "Данные чанка пока недоступны.")
                return
            }
            val citadel = manager.findCitadel(block.world.uid, block.x, block.y, block.z) ?: return
            // Removing reinforcement never breaks the physical block in the same attempt.
            event.isCancelled = true
            val result = module.actions.execute(BreakAction(
                ActionContext(event.player.uniqueId, BREAK_SOURCE), citadel.position, damage
            ))
            if (result != ActionResult.SUCCESS) {
                deny(event.player, "Действие запрещено.")
            } else {
                val removed = !citadel.isCurrent
                event.player.sendActionBar(Component.text(
                    if (removed) "Укрепление снято." else "Укреплению нанесён урон.",
                    NamedTextColor.GREEN
                ))
            }
        } catch (exception: Exception) {
            event.isCancelled = true
            report(event.player, "Could not process a citadel breaking attempt", exception)
        }
    }

    private fun deny(player: Player, message: String) {
        player.sendActionBar(Component.text(message, NamedTextColor.RED))
    }

    private fun report(player: Player, message: String, exception: Exception) {
        plugin.logger.log(Level.SEVERE, message, exception)
        deny(player, "Не удалось выполнить действие. Сообщите администратору.")
    }

    private companion object {
        val ITEM_SOURCE = Key("paper", "reinforcement_item")
        val BREAK_SOURCE = Key("paper", "player_break")
    }
}

/** Outside core's coordinate bounds no citadel can exist, so vanilla behavior stays available. */
internal fun Block.supportsCitadelData(): Boolean =
    CitadelPosition.isSupported(x, y, z)
