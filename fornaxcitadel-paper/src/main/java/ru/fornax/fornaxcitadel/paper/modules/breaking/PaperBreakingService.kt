package ru.fornax.fornaxcitadel.paper.modules.breaking

import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Door
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.modules.remove.RemoveService
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.resolver.MaterialPlacementService
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.paper.config.PaperMessages
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifierService
import ru.fornax.fornaxcitadel.paper.modules.supportsCitadelData
import java.util.logging.Level

/** Submits ordinary mining damage or creative destruction through the shared action pipeline. */
class PaperBreakingService @JvmOverloads constructor(
    private val plugin: Plugin,
    private val actions: ActionManager,
    private val citadels: CitadelFacade,
    private val remove: RemoveService,
    private val damageModifiers: PlayerDamageModifierService = PlayerDamageModifierService(),
    private val messages: PaperMessages = PaperMessages.defaults(),
    private val creativeModeEnabled: Boolean = true
) : LifecycleService {
    private val listener = PaperBreakingListener(this)
    private var enabled = false

    /** Resolves positions for reinforcement checks; the material adapter owns physical destruction. */
    fun destructionParts(block: Block, placement: MaterialPlacementService): List<Block> {
        requireAvailable(block)
        val related = placement.getRule(block).getRelatedBlock(block)?.also(::requireAvailable)
        return if ((block.blockData is Door || block.blockData is Bed) && related != null) listOf(block, related) else listOf(block)
    }

    /** Removes one logical reinforcement target, even if its material refuses physical destruction. */
    fun destroy(block: Block, reinforcements: List<CitadelHandle>, actions: List<ActionData>,
                materials: MaterialRegistry, placement: MaterialPlacementService,
                validate: () -> Boolean): ActionResult {
        check(plugin.server.isPrimaryThread && enabled) { "Destruction requires the active server thread" }
        var executingDestruction = false
        try {
            return this.actions.executeBatch(actions, prepare = {
                val parts = destructionParts(block, placement)
                enabled && reinforcements.all { it.isCurrent } && validate() && parts.none { it.isEmpty } &&
                    parts.mapNotNull { citadels.findProtecting(it.citadelPosition()) }
                        .map { it.position to it.runtimeId }.toSet() == reinforcements.map { it.position to it.runtimeId }.toSet()
            }, commit = {
                executingDestruction = true
                val material = materials.getTypeOrNull(block)
                reinforcements.forEach {
                    check(remove.remove(it) == ActionResult.SUCCESS) { "Destruction target expired" }
                }
                material?.breakNaturallyBlock(block, triggerEffect = true)
            })
        } catch (failure: CitadelResolutionUnavailableException) {
            if (executingDestruction) throw IllegalStateException("Chunk became unavailable during destruction", failure)
            return ActionResult.DENIED
        }
    }

    private fun requireAvailable(block: Block) {
        val position = block.citadelPosition()
        if (!block.world.isChunkLoaded(block.x shr 4, block.z shr 4) ||
            !citadels.isChunkLoaded(position.world, block.x shr 4, block.z shr 4)) {
            throw CitadelResolutionUnavailableException(position)
        }
    }

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
            if (!citadels.isChunkLoaded(block.world.uid, block.x shr 4, block.z shr 4)) {
                messages.sendGameplay(player, "breaking.chunk-unavailable")
                return false
            }
            citadels.findProtecting(CitadelPosition(block.world.uid, block.x, block.y, block.z))
            true
        } catch (_: CitadelResolutionUnavailableException) {
            messages.sendGameplay(player, "breaking.chunk-unavailable")
            false
        } catch (exception: Exception) {
            report(player, "Could not check a citadel breaking attempt", exception)
            false
        }
    }

    /** Cancels the original reinforced break; after removal, Player.breakBlock performs destruction separately. */
    fun breakBlock(player: Player, block: Block): Boolean {
        if (!enabled) return false
        if (!block.supportsCitadelData()) return false
        try {
            if (!citadels.isChunkLoaded(block.world.uid, block.x shr 4, block.z shr 4)) {
                messages.sendGameplay(player, "breaking.chunk-unavailable")
                return true
            }
            val position = CitadelPosition(block.world.uid, block.x, block.y, block.z)
            val citadel = citadels.findProtecting(position) ?: return false
            val destroy = creativeModeEnabled && player.gameMode == GameMode.CREATIVE
            val mode = if (destroy) DamageAction.Mode.DESTROY else DamageAction.Mode.DAMAGE
            val damage = if (destroy) Double.MAX_VALUE else damageModifiers.getDamage(citadel, player)
            val result = actions.execute(DamageAction(
                ActionContext(player.uniqueId, PlayerSource(player, BREAK_SOURCE_KEY)), position, damage, mode
            ))
            if (result != ActionResult.SUCCESS) {
                messages.sendGameplay(player, "breaking.denied")
            } else if (citadel.isCurrent) {
                messages.sendGameplay(player, "breaking.damaged")
            } else {
                if (!block.isEmpty) player.breakBlock(block)
                messages.sendGameplay(player, "breaking.removed")
            }
        } catch (_: CitadelResolutionUnavailableException) {
            messages.sendGameplay(player, "breaking.chunk-unavailable")
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
        val BREAK_SOURCE_KEY = Key("paper", "player_break")
    }
}

internal fun Block.citadelPosition() = CitadelPosition(world.uid, x, y, z)
