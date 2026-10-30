package ru.fornax.fornaxcitadel.paper.material

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.util.Key

/** Resolves material identities without assigning citadel definitions or gameplay rules. */
interface MaterialRegistry {
    fun getType(key: Key): MaterialAdapter?
    fun getTypeOrNull(stack: ItemStack): MaterialAdapter?
    fun getTypeOrNull(block: Block): MaterialAdapter?

    fun isSupportType(stack: ItemStack): Boolean = getTypeOrNull(stack) != null
    fun isSupportType(block: Block): Boolean = getTypeOrNull(block) != null

    fun getType(stack: ItemStack): MaterialAdapter = requireNotNull(getTypeOrNull(stack)) {
        "Item material ${stack.type} is not supported by ${javaClass.simpleName}"
    }

    fun getType(block: Block): MaterialAdapter = requireNotNull(getTypeOrNull(block)) {
        "Block material ${block.type} is not supported by ${javaClass.simpleName}"
    }
}

// Material.isAir uses the server's block registry; identity lookup needs no running server.
internal fun ItemStack.hasMaterial(): Boolean = amount > 0 && when (type) {
    Material.AIR, Material.CAVE_AIR, Material.VOID_AIR -> false
    else -> true
}
