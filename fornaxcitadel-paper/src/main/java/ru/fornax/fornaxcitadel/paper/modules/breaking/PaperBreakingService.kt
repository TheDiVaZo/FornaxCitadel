package ru.fornax.fornaxcitadel.paper.modules.breaking

import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.Inventory
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Chest
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.hasMaterial
import ru.fornax.fornaxcitadel.paper.modules.resolver.MaterialPlacementService
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.paper.PaperMessages
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifierService
import ru.fornax.fornaxcitadel.paper.modules.supportsCitadelData
import java.util.logging.Level

/** Submits ordinary mining damage or creative destruction through the shared action pipeline. */
class PaperBreakingService @JvmOverloads constructor(
    private val plugin: Plugin,
    private val module: CitadelModule,
    private val citadelTypes: PaperCitadelTypes,
    private val damageModifiers: PlayerDamageModifierService = PlayerDamageModifierService(),
    private val messages: PaperMessages = PaperMessages.defaults(),
    private val creativeModeEnabled: Boolean = true
) : LifecycleService {
    private val manager = module.actions.componentManager
    private val listener = PaperBreakingListener(this)
    private var enabled = false

    fun destructionParts(block: Block, placement: MaterialPlacementService): List<Block> {
        requireAvailable(block)
        val related = placement.getRule(block).getRelatedBlock(block)?.also(::requireAvailable)
        return if ((block.blockData is Door || block.blockData is Bed) && related != null) listOf(block, related) else listOf(block)
    }

    /** Destroys one logical target. A veto preserves all its parts without affecting other targets. */
    fun destroy(player: Player, block: Block, reinforcements: List<CitadelHandle>, actions: List<ActionData>,
                materials: MaterialRegistry, placement: MaterialPlacementService,
                dropBlock: Boolean, dropReinforcement: Boolean, validate: () -> Boolean,
                createEvent: ((Block) -> BlockBreakEvent)? = null): ActionResult {
        check(plugin.server.isPrimaryThread && enabled) { "Destruction requires the active server thread" }
        var executingDestruction = false
        try {
            val parts = destructionParts(block, placement)
            val data = parts.map { it.type to it.blockData.asString }
            val material = materials.getType(block)
            val survivor = if (block.blockData is Chest) placement.getRule(block).getRelatedBlock(block) else null
            val partnerData = survivor?.also(::requireAvailable)?.blockData?.asString
            fun current(): Boolean {
                parts.forEach(::requireAvailable)
                survivor?.let(::requireAvailable)
                return enabled && validate() && reinforcements.all { it.isCurrent } &&
                    parts.none { it.isEmpty } && parts.map { it.type to it.blockData.asString } == data &&
                    materials.getTypeOrNull(block)?.getKey() == material.getKey() &&
                    survivor?.blockData?.asString == partnerData &&
                    parts.mapNotNull { module.citadels.findProtecting(it.citadelPosition()) }
                        .map { it.position to it.runtimeId }.toSet() == reinforcements.map { it.position to it.runtimeId }.toSet()
            }
            var dropItem = dropBlock
            return module.actions.executeBatch(actions, prepare = {
                if (!current()) return@executeBatch false
                if (createEvent != null) for (part in parts) {
                    val event = createEvent(part)
                    plugin.server.pluginManager.callEvent(event)
                    if (event.isCancelled) return@executeBatch false
                    dropItem = dropItem && event.isDropItems
                }
                current()
            }, commit = {
                executingDestruction = true
                val state = block.state
                val blockItem = if (dropItem) oneItem(material.createItemStack(1)) else null
                val refunds = if (dropReinforcement) reinforcements.mapNotNull {
                    citadelTypes.itemKey(it)
                }.map { key ->
                    oneItem(requireNotNull(materials.getType(key)) { "Missing reinforcement material $key" }.createItemStack(1))
                } else emptyList()
                val inventories = parts.mapNotNull(::inventory)
                val contents = inventories.flatMap { it.contents.filterNotNull().filter(ItemStack::hasMaterial).map(ItemStack::clone) }
                check(current()) { "Destruction target changed while creating items" }
                reinforcements.forEach {
                    check(module.remove.remove(it) == ActionResult.SUCCESS) { "Destruction target expired" }
                }
                inventories.forEach(Inventory::clear)
                material.breakBlocks(parts)
                check(parts.all { it.isEmpty }) { "Material provider did not remove its blocks" }
                survivor?.let { other ->
                    val chest = other.blockData as Chest
                    chest.type = Chest.Type.SINGLE
                    other.setBlockData(chest, false)
                }
                val location = block.location.add(0.5, 0.5, 0.5)
                val entities = (contents + listOfNotNull(blockItem)).map { block.world.dropItemNaturally(location, it) }
                if (entities.isNotEmpty()) {
                    val event = BlockDropItemEvent(block, state, player, entities.toMutableList())
                    plugin.server.pluginManager.callEvent(event)
                    entities.filter { event.isCancelled || it !in event.items }.forEach { it.remove() }
                }
                refunds.forEach { block.world.dropItemNaturally(location, it) }
            })
        } catch (failure: CitadelResolutionUnavailableException) {
            if (executingDestruction) throw IllegalStateException("Chunk became unavailable during destruction", failure)
            return ActionResult.DENIED
        }
    }

    private fun inventory(block: Block): Inventory? = when (val state = block.getState(false)) {
        is org.bukkit.block.Chest -> state.blockInventory
        is BlockInventoryHolder -> state.inventory
        else -> null
    }

    private fun requireAvailable(block: Block) {
        val position = block.citadelPosition()
        if (!block.world.isChunkLoaded(block.x shr 4, block.z shr 4) ||
            !manager.isChunkLoaded(position.world, block.x shr 4, block.z shr 4)) {
            throw CitadelResolutionUnavailableException(position)
        }
    }

    private fun oneItem(item: ItemStack): ItemStack = item.also {
        require(it.hasMaterial() && it.amount == 1) { "A material must create exactly one item" }
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
            if (!manager.isChunkLoaded(block.world.uid, block.x shr 4, block.z shr 4)) {
                messages.sendGameplay(player, "breaking.chunk-unavailable")
                return false
            }
            module.citadels.findProtecting(CitadelPosition(block.world.uid, block.x, block.y, block.z))
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
            if (!manager.isChunkLoaded(block.world.uid, block.x shr 4, block.z shr 4)) {
                messages.sendGameplay(player, "breaking.chunk-unavailable")
                return true
            }
            val position = CitadelPosition(block.world.uid, block.x, block.y, block.z)
            val citadel = module.citadels.findProtecting(position) ?: return false
            val destroy = creativeModeEnabled && player.gameMode == GameMode.CREATIVE
            val mode = if (destroy) DamageAction.Mode.DESTROY else DamageAction.Mode.DAMAGE
            val damage = if (destroy) Double.MAX_VALUE else damageModifiers.getDamage(citadel, player)
            val result = module.actions.execute(DamageAction(
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
