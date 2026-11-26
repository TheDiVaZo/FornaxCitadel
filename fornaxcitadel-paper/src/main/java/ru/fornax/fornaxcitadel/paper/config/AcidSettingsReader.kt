package ru.fornax.fornaxcitadel.paper.config

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidSettings
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidType

object AcidSettingsReader {
    fun read(config: ConfigurationSection): AcidSettings {
        val value = config.get("acid", null) ?: return AcidSettings()
        val acid = mapping(value, "acid", setOf("enabled", "activation", "types"))
        val activation = acid["activation"]?.let { mapping(it, "acid.activation", setOf("max-distance")) }.orEmpty()
        val types = if (acid.containsKey("types")) mapping(acid["types"], "acid.types").map { (id, raw) ->
            val path = "acid.types.$id"
            val type = mapping(raw, path, setOf("material", "max-targets", "maturation-multiplier", "enabled"))
            val name = type["material"]
            require(name is String) { "$path.material must name a Minecraft block" }
            val material = Material.matchMaterial(name)
                ?: throw IllegalArgumentException("Unknown $path.material: $name")
            val multiplier = type["maturation-multiplier"]
            require(multiplier is Number) { "$path.maturation-multiplier must be a number" }
            AcidType(id, material, integer(type["max-targets"], "$path.max-targets"),
                multiplier.toDouble(), boolean(type, "enabled", true, path))
        } else AcidSettings.defaults()
        return AcidSettings(boolean(acid, "enabled", true, "acid"),
            if (activation.containsKey("max-distance")) integer(activation["max-distance"], "acid.activation.max-distance") else 5,
            types)
    }

    private fun mapping(value: Any?, path: String, allowed: Set<String>? = null): Map<String, Any?> {
        val map = readLocalMapping(value, path)
        require(allowed == null || map.keys.all { it in allowed }) { "Unknown field in $path: ${map.keys - allowed.orEmpty()}" }
        return map
    }

    private fun boolean(map: Map<String, Any?>, key: String, default: Boolean, path: String): Boolean {
        if (!map.containsKey(key)) return default
        return map[key] as? Boolean ?: throw IllegalArgumentException("$path.$key must be a boolean")
    }

    private fun integer(value: Any?, path: String): Int {
        require(value is Number) { "$path must be an integer" }
        return try { value.exactInt() }
        catch (failure: RuntimeException) { throw IllegalArgumentException("$path must be an integer fitting in Int", failure) }
    }
}
