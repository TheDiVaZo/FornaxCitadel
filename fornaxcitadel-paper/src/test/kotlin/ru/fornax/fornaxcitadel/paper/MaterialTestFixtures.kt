package ru.fornax.fornaxcitadel.paper

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.material.MaterialAdapter
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry

/** A separately supplied material provider, using its own tag and optional block identity. */
internal class TestMaterialAdapter(
    private val materialKey: Key,
    private val base: Material = Material.AMETHYST_SHARD,
    private val taggedBlock: Block? = null,
    private val itemTag: String = materialKey.asString()
) : MaterialAdapter {
    private val tagKey = NamespacedKey("thirdparty", "material")

    override fun getKey(): Key = materialKey

    override fun isConsists(itemStack: ItemStack): Boolean = itemStack.amount > 0 && itemStack.type == base &&
        itemStack.persistentDataContainer.get(tagKey, PersistentDataType.STRING) == itemTag

    override fun isConsists(block: Block): Boolean = block === taggedBlock

    override fun createItemStack(amount: Int): ItemStack = TestItemStack(base, amount).apply {
        data.values[tagKey] = itemTag
    }
}

internal class TestMaterialRegistry(private vararg val adapters: MaterialAdapter) : MaterialRegistry {
    override fun getType(key: Key): MaterialAdapter? = adapters.firstOrNull { it.getKey() == key }
    override fun getTypeOrNull(stack: ItemStack): MaterialAdapter? = adapters.firstOrNull { it.isConsists(stack) }
    override fun getTypeOrNull(block: Block): MaterialAdapter? = adapters.firstOrNull { it.isConsists(block) }
}
