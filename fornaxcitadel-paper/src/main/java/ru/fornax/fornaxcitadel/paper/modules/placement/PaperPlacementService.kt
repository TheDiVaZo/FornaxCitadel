package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.NamedActionSource
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.PaperMessages
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.supportsCitadelData
import java.util.logging.Level

/** Resolves consumable materials and installs their configured definition through core actions. */
class PaperPlacementService @JvmOverloads constructor(
    private val plugin: Plugin,
    private val module: CitadelModule,
    private val materials: MaterialRegistry,
    settings: PlacementSettings = PlacementSettings(),
    private val messages: PaperMessages = PaperMessages.defaults()
) : LifecycleService {
    private val definitionsByMaterial = mutableMapOf<Key, Key>()
    private val blockValidator = ReinforceableBlockValidator(plugin.server, module.citadels)
    private val blockListValidator = PlacementBlockListValidator(
        plugin.server, materials, settings.listType, settings.blocks, module.citadels
    )
    private val listener = PaperPlacementListener(this)
    private var enabled = false

    override fun enable() {
        if (enabled) return
        try {
            module.actions.validatorCoordinator.registerValidator(blockValidator)
            module.actions.validatorCoordinator.registerValidator(blockListValidator)
            plugin.server.pluginManager.registerEvents(listener, plugin)
            enabled = true
        } catch (failure: Throwable) {
            unregister()
            throw failure
        }
    }

    override fun disable() {
        if (!enabled) return
        unregister()
        enabled = false
    }

    private fun unregister() {
        HandlerList.unregisterAll(listener)
        module.actions.validatorCoordinator.unregisterValidator(blockListValidator)
        module.actions.validatorCoordinator.unregisterValidator(blockValidator)
    }

    /**
     * Binds an item key to a citadel definition without resolving its adapter.
     * The material provider may register later; item recognition uses the current registry on each interaction.
     */
    fun registerMaterial(materialKey: Key, definitionKey: Key) {
        val existing = definitionsByMaterial.putIfAbsent(materialKey, definitionKey)
        require(existing == null || existing == definitionKey) {
            "Material $materialKey is already bound to citadel definition $existing"
        }
    }

    fun heldMaterialKey(player: Player, item: ItemStack?): Key? {
        val key = resolveMaterialKey(item) ?: return null
        if (key !in definitionsByMaterial) return null
        return key.takeIf { resolveMaterialKey(player.inventory.itemInMainHand) == it }
    }

    fun place(player: Player, block: Block, materialKey: Key) {
        if (!enabled) return
        val definitionKey = definitionsByMaterial[materialKey] ?: return
        val held = player.inventory.itemInMainHand
        if (resolveMaterialKey(held) != materialKey) return
        if (!block.supportsCitadelData()) {
            messages.sendGameplay(player, "placement.invalid-block")
            return
        }

        try {
            val result = module.actions.execute(PlaceAction(
                ActionContext(player.uniqueId, ITEM_SOURCE),
                CitadelPosition(block.world.uid, block.x, block.y, block.z),
                definitionKey
            ))
            if (result != ActionResult.SUCCESS) {
                messages.sendGameplay(player, "placement.denied")
                return
            }
            if (player.gameMode != GameMode.CREATIVE) {
                held.amount--
                player.inventory.setItemInMainHand(if (held.amount == 0) null else held)
            }
            messages.sendGameplay(player, "placement.success")
        } catch (exception: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not place a citadel", exception)
            messages.sendGameplay(player, "placement.error")
        }
    }

    private fun resolveMaterialKey(item: ItemStack?): Key? {
        if (item == null || item.amount <= 0) return null
        return materials.getTypeOrNull(item)?.getKey()
    }

    private companion object {
        val ITEM_SOURCE = NamedActionSource(Key("paper", "reinforcement_item"))
    }
}
