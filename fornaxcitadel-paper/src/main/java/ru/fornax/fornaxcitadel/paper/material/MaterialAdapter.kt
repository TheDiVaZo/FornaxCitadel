package ru.fornax.fornaxcitadel.paper.material

import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.util.Key

/**
 * A material identity with platform-specific matching and item creation.
 * Identity queries and item creation are trusted synchronous operations: they must not mutate
 * their inputs, the world, inventories, citadels or profiles. Only [breakNaturallyBlock] mutates the world.
 */
interface MaterialAdapter {
    fun getKey(): Key
    fun isConsists(itemStack: ItemStack): Boolean
    fun isConsists(block: Block): Boolean

    /** Returns a new, detached stack of the requested amount without modifying shared templates. */
    fun createItemStack(amount: Int): ItemStack

    /**
     * Breaks this block through the provider's natural breaking routine, including its drops and
     * optional effects. Returns the provider's result; unsupported materials return false.
     */
    fun breakNaturallyBlock(block: Block, triggerEffect: Boolean): Boolean

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

        override fun breakNaturallyBlock(block: Block, triggerEffect: Boolean): Boolean =
            materialRegistry.getType(key)?.breakNaturallyBlock(block, triggerEffect) ?: false
    }
}
