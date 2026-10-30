package ru.fornax.fornaxcitadel.paper.material

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.PaperCitadelTypes

/** Additional material registry for built-in installation items, identified by their persistent tag. */
class CitadelItems(plugin: Plugin, private val citadelTypes: PaperCitadelTypes) : MaterialRegistry {
    private val typeKey = NamespacedKey(plugin, "citadel_type")
    private val types = citadelTypes.keys.associateBy(Key::asString)
    private val adapters = types.values.associateWith { InstallationMaterial(it) }

    override fun getType(key: Key): MaterialAdapter? = adapters[key]

    override fun getTypeOrNull(stack: ItemStack): MaterialAdapter? = typeOf(stack)?.let(adapters::get)

    // Installation items reinforce existing blocks; they are not custom block materials.
    override fun getTypeOrNull(block: Block): MaterialAdapter? = null

    fun create(type: Key, amount: Int = 1): ItemStack {
        require(type.asString() in types) { "Unknown citadel type: $type" }
        require(amount in 1..64) { "Item amount must be between 1 and 64" }
        val item = ItemStack.of(Material.AMETHYST_SHARD, amount)
        val meta = checkNotNull(item.itemMeta) { "Installation item has no metadata" }
        val title = "Укрепление: ${citadelTypes.name(type) ?: type.asString()}"
        meta.displayName(Component.text(title, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
        meta.lore(listOf(Component.text("ПКМ по блоку для установки", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false)))
        meta.persistentDataContainer.set(typeKey, PersistentDataType.STRING, type.asString())
        item.itemMeta = meta
        return item
    }

    fun typeOf(item: ItemStack?): Key? {
        if (item == null || item.type != Material.AMETHYST_SHARD || item.amount <= 0 || !item.hasItemMeta()) {
            return null
        }
        val meta = item.itemMeta ?: return null
        val encoded = meta.persistentDataContainer.get(typeKey, PersistentDataType.STRING)
            ?: return null
        return types[encoded]
    }

    private inner class InstallationMaterial(private val key: Key) : MaterialAdapter {
        override fun getKey(): Key = key

        override fun isConsists(itemStack: ItemStack): Boolean = typeOf(itemStack) == key

        override fun isConsists(block: Block): Boolean = false

        override fun createItemStack(amount: Int): ItemStack = create(key, amount)
    }
}
