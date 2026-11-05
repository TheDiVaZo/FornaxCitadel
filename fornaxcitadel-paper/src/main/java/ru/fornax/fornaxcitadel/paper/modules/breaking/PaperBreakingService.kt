package ru.fornax.fornaxcitadel.paper.modules.breaking

import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.NamedActionSource
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.paper.PaperMessages
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifierService
import ru.fornax.fornaxcitadel.paper.modules.supportsCitadelData
import java.util.logging.Level

/** Submits ordinary mining damage or creative destruction through the shared action pipeline. */
class PaperBreakingService @JvmOverloads constructor(
    private val plugin: Plugin,
    private val module: CitadelModule,
    private val damageModifiers: PlayerDamageModifierService = PlayerDamageModifierService(),
    private val messages: PaperMessages = PaperMessages.defaults(),
    private val creativeModeEnabled: Boolean = true
) : LifecycleService {
    private val manager = module.actions.componentManager
    private val listener = PaperBreakingListener(this)
    private var enabled = false

    override fun enable() {
        if (enabled) return
        try {
            plugin.server.pluginManager.registerEvents(listener, plugin)
            enabled = true
        } catch (failure: Throwable) {
            HandlerList.unregisterAll(listener)
            throw failure
        }
    }

    override fun disable() {
        if (!enabled) return
        HandlerList.unregisterAll(listener)
        enabled = false
    }

    fun canStartBreaking(player: Player, block: Block): Boolean {
        if (!enabled || !block.supportsCitadelData()) return true
        return try {
            if (!manager.isChunkLoaded(block.world.uid, block.x shr 4, block.z shr 4)) {
                messages.sendGameplay(player, "breaking.chunk-unavailable")
                return false
            }
            true
        } catch (exception: Exception) {
            report(player, "Could not check a citadel breaking attempt", exception)
            false
        }
    }

    /** Returns true when vanilla destruction must be cancelled, including after removing reinforcement. */
    fun breakBlock(player: Player, block: Block): Boolean {
        if (!enabled) return false
        if (!block.supportsCitadelData()) return false
        try {
            if (!manager.isChunkLoaded(block.world.uid, block.x shr 4, block.z shr 4)) {
                messages.sendGameplay(player, "breaking.chunk-unavailable")
                return true
            }
            val citadel = manager.findCitadel(block.world.uid, block.x, block.y, block.z) ?: return false
            val destroy = creativeModeEnabled && player.gameMode == GameMode.CREATIVE
            val mode = if (destroy) DamageAction.Mode.DESTROY else DamageAction.Mode.DAMAGE
            val damage = if (destroy) Double.MAX_VALUE else damageModifiers.getDamage(citadel, player)
            val result = module.actions.execute(DamageAction(
                ActionContext(player.uniqueId, BREAK_SOURCE), citadel.position, damage, mode
            ))
            if (result != ActionResult.SUCCESS) {
                messages.sendGameplay(player, "breaking.denied")
            } else {
                messages.sendGameplay(player, if (!citadel.isCurrent) "breaking.removed" else "breaking.damaged")
            }
        } catch (exception: Exception) {
            report(player, "Could not process a citadel breaking attempt", exception)
        }
        return true
    }

    private fun report(player: Player, message: String, exception: Exception) {
        plugin.logger.log(Level.SEVERE, message, exception)
        messages.sendGameplay(player, "breaking.error")
    }

    private companion object {
        val BREAK_SOURCE = NamedActionSource(Key("paper", "player_break"))
    }
}
