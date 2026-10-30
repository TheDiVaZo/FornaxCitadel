package ru.fornax.fornaxcitadel.paper.material

import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.util.Key

/** A material identity with platform-specific matching and item creation. */
interface MaterialAdapter {
    fun getKey(): Key
    fun isConsists(itemStack: ItemStack): Boolean
    fun isConsists(block: Block): Boolean
    fun createItemStack(amount: Int): ItemStack

    /** Resolves on use so integrations can register their materials after configuration is read. */
    class Wrapper(private val key: Key, private val materialRegistry: MaterialRegistry) : MaterialAdapter {
        override fun getKey(): Key = key

        override fun isConsists(itemStack: ItemStack): Boolean =
            materialRegistry.getType(key)?.isConsists(itemStack) ?: false

        override fun isConsists(block: Block): Boolean =
            materialRegistry.getType(key)?.isConsists(block) ?: false

        override fun createItemStack(amount: Int): ItemStack {
            require(amount > 0) { "Item amount must be positive: $amount" }
            val material = checkNotNull(materialRegistry.getType(key)) {
                "Material $key is not registered in ${materialRegistry.javaClass.simpleName}"
            }
            return material.createItemStack(amount)
        }
    }
}
