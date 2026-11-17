package ru.fornax.fornaxcitadel.paper.material

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.util.Key

/** Vanilla materials are the fallback after registered custom material providers. */
object BukkitMaterialRegistry : MaterialRegistry {
    private val materials = Material.entries.filterNot(Material::isLegacy)
        .associateWith { material ->
            if (material.isBlock && material.hardness < 0) UnbreakableMaterialAdapter(material)
            else SimpleMaterialAdapter(material)
        }
    private val keyToMaterial = materials.values.associateBy(MaterialAdapter::getKey)

    override fun getType(key: Key): MaterialAdapter? = keyToMaterial[key]

    override fun getTypeOrNull(stack: ItemStack): MaterialAdapter? =
        if (stack.hasMaterial()) materials[stack.type] else null

    override fun getTypeOrNull(block: Block): MaterialAdapter? = materials[block.type]

    operator fun get(material: Material): MaterialAdapter = requireNotNull(materials[material]) {
        "Legacy material $material is not supported"
    }

    private class UnbreakableMaterialAdapter(material: Material) : MaterialAdapter by SimpleMaterialAdapter(material) {
        override fun breakNaturallyBlock(block: Block, triggerEffect: Boolean): Boolean = false
    }

    class SimpleMaterialAdapter(val material: Material) : MaterialAdapter {
        private val key: Key

        init {
            require(!material.isLegacy) { "Legacy material $material is not supported" }
            val namespacedKey = material.key
            key = Key(namespacedKey.namespace, namespacedKey.key)
        }

        override fun getKey(): Key = key

        override fun isConsists(itemStack: ItemStack): Boolean =
            itemStack.hasMaterial() && itemStack.type == material

        override fun isConsists(block: Block): Boolean = block.type == material

        override fun breakNaturallyBlock(block: Block, triggerEffect: Boolean): Boolean {
            if (!isConsists(block)) return false
            val nativeKey = block.type.key
            if (Key(nativeKey.namespace, nativeKey.key) != getKey()) return false
            return block.breakNaturally(triggerEffect)
        }

        override fun createItemStack(amount: Int): ItemStack {
            require(amount > 0) { "Item amount must be positive: $amount" }
            require(material.isItem) { "Material $key has no item form" }
            return ItemStack.of(material, amount)
        }
    }
}
