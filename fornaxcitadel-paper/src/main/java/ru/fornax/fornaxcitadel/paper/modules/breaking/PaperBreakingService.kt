package ru.fornax.fornaxcitadel.paper.modules.breaking

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.breaking.BreakAction
import ru.fornax.fornaxcitadel.modules.breaking.BreakDecision
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifierService
import ru.fornax.fornaxcitadel.paper.modules.supportsCitadelData
import java.util.logging.Level

/** Resolves player mining attempts through core break policies and actions. */
class PaperBreakingService @JvmOverloads constructor(
    private val damageModifiers: PlayerDamageModifierService = PlayerDamageModifierService()
) {
    private lateinit var plugin: Plugin
    private lateinit var module: CitadelModule
    private lateinit var manager: SimpleComponentManager

    fun init(plugin: Plugin, module: CitadelModule) {
        check(!::module.isInitialized) { "Breaking service is already initialized" }
        this.plugin = plugin
        this.module = module
        this.manager = module.actions.componentManager
        plugin.server.pluginManager.registerEvents(PaperBreakingListener(this), plugin)
    }

    fun prepareBreak(player: Player, block: Block): MiningDecision {
        if (!block.supportsCitadelData()) return MiningDecision.DEFAULT
        return try {
            if (!manager.isChunkLoaded(block.world.uid, block.x shr 4, block.z shr 4)) {
                deny(player, "Данные чанка пока недоступны.")
                return MiningDecision.DENY
            }
            val citadel = manager.findCitadel(block.world.uid, block.x, block.y, block.z)
                ?: return MiningDecision.DEFAULT
            val kind = citadel.getComponent(CitadelKind::class.java) ?: return MiningDecision.DEFAULT
            val policy = module.breaking.findPolicy(kind.definitionKey) ?: return MiningDecision.DEFAULT
            val context = ActionContext(player.uniqueId, BREAK_SOURCE)
            if (policy.decide(context, citadel) == BreakDecision.REMOVE) MiningDecision.INSTANT
            else MiningDecision.DEFAULT
        } catch (exception: Exception) {
            report(player, "Could not check a citadel breaking attempt", exception)
            MiningDecision.DENY
        }
    }

    /** Returns true when vanilla destruction must be cancelled, including after removing reinforcement. */
    fun breakBlock(player: Player, block: Block): Boolean {
        if (!block.supportsCitadelData()) return false
        try {
            if (!manager.isChunkLoaded(block.world.uid, block.x shr 4, block.z shr 4)) {
                deny(player, "Данные чанка пока недоступны.")
                return true
            }
            val citadel = manager.findCitadel(block.world.uid, block.x, block.y, block.z) ?: return false
            val damage = damageModifiers.getDamage(citadel, player)
            val result = module.actions.execute(BreakAction(
                ActionContext(player.uniqueId, BREAK_SOURCE), citadel.position, damage
            ))
            if (result != ActionResult.SUCCESS) {
                deny(player, "Действие запрещено.")
            } else {
                player.sendActionBar(Component.text(
                    if (!citadel.isCurrent) "Укрепление снято." else "Укреплению нанесён урон.",
                    NamedTextColor.GREEN
                ))
            }
        } catch (exception: Exception) {
            report(player, "Could not process a citadel breaking attempt", exception)
        }
        return true
    }

    private fun deny(player: Player, message: String) {
        player.sendActionBar(Component.text(message, NamedTextColor.RED))
    }

    private fun report(player: Player, message: String, exception: Exception) {
        plugin.logger.log(Level.SEVERE, message, exception)
        deny(player, "Не удалось выполнить действие. Сообщите администратору.")
    }

    enum class MiningDecision { DEFAULT, INSTANT, DENY }

    private companion object {
        val BREAK_SOURCE = Key("paper", "player_break")
    }
}
