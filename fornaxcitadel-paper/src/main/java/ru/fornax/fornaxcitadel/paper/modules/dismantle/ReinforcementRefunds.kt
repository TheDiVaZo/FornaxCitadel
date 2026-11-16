package ru.fornax.fornaxcitadel.paper.modules.dismantle

import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.hasMaterial

/** Shared refund rules; callers decide whether their operation guarantees a refund. */
internal class ReinforcementRefunds(
    private val materials: MaterialRegistry,
    private val settings: DismantleSettings,
    private val random: () -> Double
) {
    fun chance(guaranteed: Boolean): Double = if (guaranteed) 1.0 else settings.refundChance

    fun create(itemKey: Key?, chance: Double): ItemStack? {
        if (itemKey == null || chance <= 0.0) return null
        val material = requireNotNull(materials.getType(itemKey)) { "Unknown reinforcement material $itemKey" }
        return material.createItemStack(1).also {
            require(it.hasMaterial() && it.amount == 1) { "Reinforcement material $itemKey must create one item" }
        }
    }

    fun shouldDrop(chance: Double): Boolean = chance == 1.0 || chance > 0.0 && random() < chance
}
