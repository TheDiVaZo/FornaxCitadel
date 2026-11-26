package ru.fornax.fornaxcitadel.paper.config

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.modules.placement.BlockListType
import ru.fornax.fornaxcitadel.paper.modules.placement.PlacementSettings
import java.util.Locale

object PlacementSettingsReader {
    fun read(config: ConfigurationSection): PlacementSettings {
        val path = "placement.block-list"
        // Bundled whitelist defaults must not fill an explicitly configured blacklist.
        val placement = config.get("placement", null)
        require(placement == null || placement is ConfigurationSection || placement is Map<*, *>) {
            "placement must be a mapping"
        }
        val configured = when (placement) {
            is ConfigurationSection -> placement.get("block-list", null)
            is Map<*, *> -> placement["block-list"]
            else -> null
        }
        if (configured == null) return PlacementSettings()
        val values = when (configured) {
            is ConfigurationSection -> configured.getValues(false).filterKeys { configured.contains(it, true) }
            is Map<*, *> -> configured
            else -> throw IllegalArgumentException("$path must be a mapping with type and blocks")
        }
        val rawType = if (values.containsKey("type")) values["type"] else BlockListType.WHITELIST.name
        require(rawType is String) { "$path.type must be BLACKLIST or WHITELIST" }
        val listType = BlockListType.entries.firstOrNull { it.name.equals(rawType, ignoreCase = true) }
        requireNotNull(listType) { "Unknown $path.type: $rawType; expected BLACKLIST or WHITELIST" }
        val blocks = if (values.containsKey("blocks")) {
            readBlocks(values["blocks"], "$path.blocks")
        } else if (listType == BlockListType.BLACKLIST) emptySet() else PlacementSettings.DEFAULT_BLOCKS
        return PlacementSettings(listType, blocks)
    }

    private fun readBlocks(value: Any?, path: String): Set<Key> {
        require(value is List<*>) { "$path must be a list of material keys" }
        return value.mapTo(linkedSetOf()) { entry ->
            require(entry is String && entry.isNotBlank()) { "$path entries must be non-empty material keys" }
            val separator = entry.indexOf(':')
            if (separator >= 0) {
                require(separator > 0 && separator < entry.lastIndex) {
                    "$path material keys must contain both namespace and value: $entry"
                }
                val namespace = entry.substring(0, separator)
                val value = entry.substring(separator + 1)
                require(Key.parseableNamespace(namespace) && Key.parseableValue(value)) {
                    "Invalid $path material key: $entry"
                }
                Key(namespace, value).also { PlacementSettings.requireValidKey(it) }
            } else {
                val material = Material.getMaterial(entry.uppercase(Locale.ROOT))
                require(material != null && !material.isLegacy) {
                    "Unknown $path Bukkit material: $entry; custom materials require namespace:key"
                }
                val key = material.key
                Key(key.namespace, key.key)
            }
        }
    }
}
