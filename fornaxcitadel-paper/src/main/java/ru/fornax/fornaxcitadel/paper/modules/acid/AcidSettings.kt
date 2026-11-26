package ru.fornax.fornaxcitadel.paper.modules.acid

import org.bukkit.Material

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

data class AcidSettings(val enabled: Boolean = true, val maxDistance: Int = 5,
                        val types: List<AcidType> = defaults()) {
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
    }
}

/** Definitions are resolved from current block material, never persisted on individual citadels. */
class AcidRegistry(settings: AcidSettings) {
    val settings = settings.copy(types = java.util.Collections.unmodifiableList(settings.types.toList()))
    private val active = this.settings.types.filter { it.enabled }.associateBy { it.material }
    fun find(material: Material): AcidType? = if (settings.enabled) active[material] else null
    fun isDisabled(material: Material): Boolean = settings.types.any { it.material == material } && find(material) == null
}
