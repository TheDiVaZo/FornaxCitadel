package ru.fornax.fornaxcitadel.paper.modules.placement

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.supportsCitadelData
import java.util.logging.Level

/** Resolves consumable materials and installs their configured definition through core actions. */
class PaperPlacementService @JvmOverloads constructor(
    private val materials: MaterialRegistry,
    private val settings: PlacementSettings = PlacementSettings()
) {
    private val definitionsByMaterial = mutableMapOf<Key, Key>()
    private lateinit var plugin: Plugin
    private lateinit var module: CitadelModule

    fun init(plugin: Plugin, module: CitadelModule) {
        check(!::module.isInitialized) { "Placement service is already initialized" }
        this.plugin = plugin
        this.module = module
        module.actions.validatorCoordinator.registerValidator(ReinforceableBlockValidator(plugin.server))
        module.actions.validatorCoordinator.registerValidator(
            PlacementBlockListValidator(plugin.server, materials, settings.listType, settings.blocks)
        )
        plugin.server.pluginManager.registerEvents(PaperPlacementListener(this), plugin)
    }

    /** Material identity and citadel definition are independent, including for third-party items. */
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
        val definitionKey = definitionsByMaterial[materialKey] ?: return
        val held = player.inventory.itemInMainHand
        if (resolveMaterialKey(held) != materialKey) return
        if (!block.supportsCitadelData()) {
            deny(player, "В этой точке нельзя установить укрепление.")
            return
        }

        try {
            val result = module.actions.execute(PlaceAction(
                ActionContext(player.uniqueId, ITEM_SOURCE),
                CitadelPosition(block.world.uid, block.x, block.y, block.z),
                definitionKey
            ))
            if (result != ActionResult.SUCCESS) {
                deny(player, "Не удалось установить укрепление.")
                return
            }
            if (player.gameMode != GameMode.CREATIVE) {
                held.amount--
                player.inventory.setItemInMainHand(if (held.amount == 0) null else held)
            }
            player.sendActionBar(Component.text("Укрепление установлено.", NamedTextColor.GREEN))
        } catch (exception: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not place a citadel", exception)
            deny(player, "Не удалось выполнить действие. Сообщите администратору.")
        }
    }

    private fun resolveMaterialKey(item: ItemStack?): Key? {
        if (item == null || item.amount <= 0) return null
        return materials.getTypeOrNull(item)?.getKey()
    }

    private fun deny(player: Player, message: String) {
        player.sendActionBar(Component.text(message, NamedTextColor.RED))
    }

    private companion object {
        val ITEM_SOURCE = Key("paper", "reinforcement_item")
    }
}
