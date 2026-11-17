package ru.fornax.fornaxcitadel.paper.config

import org.bukkit.configuration.ConfigurationSection
import java.math.BigDecimal

/** Reads only explicitly configured entries, even when Bukkit exposes copied defaults. */
internal fun readLocalMapping(value: Any?, path: String): Map<String, Any?> {
    val values = when (value) {
        is ConfigurationSection -> value.getValues(false).filterKeys { value.contains(it, true) }
        is Map<*, *> -> value
        else -> throw IllegalArgumentException("$path must be a mapping")
    }
    return values.entries.associate { (key, entry) ->
        require(key is String) { "$path keys must be strings" }
        key to entry
    }
}

internal fun Number.exactInt(): Int = BigDecimal(toString()).intValueExact()

internal fun Number.exactLong(): Long = BigDecimal(toString()).longValueExact()
