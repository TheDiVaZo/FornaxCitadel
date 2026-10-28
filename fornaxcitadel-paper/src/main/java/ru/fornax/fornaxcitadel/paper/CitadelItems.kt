package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.util.Key

/** Recognizes installation items by their persistent tag, never by their display name. */
class CitadelItems(plugin: Plugin) {
    private val typeKey = NamespacedKey(plugin, "citadel_type")
    private val types = PaperCitadelTypes.names.mapNotNull(PaperCitadelTypes::find).associateBy(Key::asString)

    fun create(type: Key, amount: Int = 1): ItemStack {
        require(type.asString() in types) { "Unknown citadel type: $type" }
        require(amount in 1..64) { "Item amount must be between 1 and 64" }
        val item = ItemStack.of(Material.AMETHYST_SHARD, amount)
        val meta = checkNotNull(item.itemMeta) { "Installation item has no metadata" }
        val title = when (type) {
            PaperCitadelTypes.REINFORCED -> "Укрепление"
            PaperCitadelTypes.MATURING -> "Созревающее укрепление"
            PaperCitadelTypes.DEV -> "Тестовое укрепление"
            PaperCitadelTypes.INVULNERABLE -> "Неуязвимое укрепление"
            else -> error("Unknown citadel type: $type")
        }
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
}
