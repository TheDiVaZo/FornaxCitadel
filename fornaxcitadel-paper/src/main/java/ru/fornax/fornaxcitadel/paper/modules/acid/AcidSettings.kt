package ru.fornax.fornaxcitadel.paper.modules.acid

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import java.math.BigDecimal

data class AcidType(val id: String, val material: Material, val maxTargets: Int,
                    val maturationMultiplier: Double, val enabled: Boolean = true) {
    init {
        require(id.matches(Regex("[a-z0-9_-]+"))) { "Invalid acid type identifier: $id" }
        require(!material.isLegacy && material.isBlock && material !in AIR) { "acid.types.$id.material must be a non-air block" }
        require(maxTargets in 1..6) { "acid.types.$id.max-targets must be between 1 and 6" }
        require(maturationMultiplier.isFinite() && maturationMultiplier >= 1.0) {
            "acid.types.$id.maturation-multiplier must be finite and at least 1"
        }
    }

    private companion object { val AIR = setOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR) }
}

data class AcidDrops(val targetBlock: Boolean = true, val targetReinforcement: Boolean = false,
                     val acidBlock: Boolean = false, val acidReinforcement: Boolean = true)

data class AcidSettings(val enabled: Boolean = true, val maxDistance: Int = 5,
                        val drops: AcidDrops = AcidDrops(), val types: List<AcidType> = defaults()) {
    init {
        require(maxDistance > 0) { "acid.activation.max-distance must be a positive integer" }
        require(types.map { it.id }.distinct().size == types.size) { "Duplicate acid type identifiers" }
        val active = types.filter { it.enabled }
        require(active.map { it.material }.distinct().size == active.size) { "Duplicate active acid materials" }
    }

    companion object {
        fun defaults(): List<AcidType> = listOf(
            AcidType("gold", Material.GOLD_BLOCK, 1, 2.0),
            AcidType("diamond", Material.DIAMOND_BLOCK, 2, 3.0),
            AcidType("netherite", Material.NETHERITE_BLOCK, 6, 4.0)
        )

        fun read(root: ConfigurationSection): AcidSettings {
            val value = root.get("acid", null) ?: return AcidSettings()
            val acid = mapping(value, "acid", setOf("enabled", "activation", "drops", "types"))
            val activation = acid["activation"]?.let { mapping(it, "acid.activation", setOf("max-distance")) }.orEmpty()
            val drops = acid["drops"]?.let { mapping(it, "acid.drops",
                setOf("target-block", "target-reinforcement", "acid-block", "acid-reinforcement")) }.orEmpty()
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
            } else defaults()
            return AcidSettings(boolean(acid, "enabled", true, "acid"),
                if (activation.containsKey("max-distance")) integer(activation["max-distance"], "acid.activation.max-distance") else 5,
                AcidDrops(boolean(drops, "target-block", true, "acid.drops"),
                    boolean(drops, "target-reinforcement", false, "acid.drops"),
                    boolean(drops, "acid-block", false, "acid.drops"),
                    boolean(drops, "acid-reinforcement", true, "acid.drops")), types)
        }

        private fun mapping(value: Any?, path: String, allowed: Set<String>? = null): Map<String, Any?> {
            val map = when (value) {
                is ConfigurationSection -> value.getValues(false).filterKeys { value.contains(it, true) }
                is Map<*, *> -> value
                else -> throw IllegalArgumentException("$path must be a mapping")
            }.entries.associate { (key, entry) ->
                require(key is String) { "$path keys must be strings" }
                key to entry
            }
            require(allowed == null || map.keys.all { it in allowed }) { "Unknown field in $path: ${map.keys - allowed.orEmpty()}" }
            return map
        }

        private fun boolean(map: Map<String, Any?>, key: String, default: Boolean, path: String): Boolean {
            if (!map.containsKey(key)) return default
            return map[key] as? Boolean ?: throw IllegalArgumentException("$path.$key must be a boolean")
        }

        private fun integer(value: Any?, path: String): Int {
            require(value is Number) { "$path must be an integer" }
            return try { BigDecimal(value.toString()).intValueExact() }
            catch (failure: RuntimeException) { throw IllegalArgumentException("$path must be an integer fitting in Int", failure) }
        }
    }
}

/** Definitions are resolved from current block material, never persisted on individual citadels. */
class AcidRegistry(settings: AcidSettings) {
    val settings = settings.copy(types = java.util.Collections.unmodifiableList(settings.types.toList()))
    private val active = this.settings.types.filter { it.enabled }.associateBy { it.material }
    fun find(material: Material): AcidType? = if (settings.enabled) active[material] else null
    fun isDisabled(material: Material): Boolean = settings.types.any { it.material == material } && find(material) == null
}
