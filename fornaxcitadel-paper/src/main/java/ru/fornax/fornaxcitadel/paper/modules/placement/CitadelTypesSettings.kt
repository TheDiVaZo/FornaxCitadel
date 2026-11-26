package ru.fornax.fornaxcitadel.paper.modules.placement

import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.HealthDefinition

data class CitadelTypesSettings(val types: Map<String, CitadelTypeSettings> = DEFAULT_TYPES) {
    init {
        val itemOwners = mutableMapOf<Key, String>()
        types.forEach { (id, settings) ->
            requireValidId(id)
            val item = settings.item ?: return@forEach
            val previous = itemOwners.putIfAbsent(item, id)
            require(previous == null) {
                "citadel-types.$id.item duplicates citadel-types.$previous.item: $item"
            }
        }
    }

    companion object {
        val DEFAULT_TYPES: Map<String, CitadelTypeSettings> = linkedMapOf(
            "reinforced_light" to CitadelTypeSettings(Key("minecraft", "copper_ingot"), lvl = 1,
                health = HealthDefinition(10.0)),
            "reinforced" to CitadelTypeSettings(Key("minecraft", "iron_ingot"), lvl = 2,
                health = HealthDefinition(20.0)),
            "reinforced_heavy" to CitadelTypeSettings(Key("minecraft", "diamond"), lvl = 3,
                health = HealthDefinition(30.0)),
            "maturing_fast" to CitadelTypeSettings(Key("minecraft", "amethyst_shard"), lvl = 2,
                health = HealthDefinition(20.0), maturing = MaturingSettings(300, 2.0)),
            "maturing" to CitadelTypeSettings(Key("minecraft", "emerald"), lvl = 4,
                health = HealthDefinition(40.0), maturing = MaturingSettings(600, 4.0)),
            "maturing_strong" to CitadelTypeSettings(Key("minecraft", "netherite_ingot"), lvl = 5,
                health = HealthDefinition(80.0), maturing = MaturingSettings(1800, 4.0)),
            "invulnerable" to CitadelTypeSettings(Key("minecraft", "nether_star"), lvl = 999,
                health = HealthDefinition(maxHealth = null, invulnerable = true))
        )
        private val ID_PATTERN = Regex("[a-z0-9_-]+")

        internal fun requireValidId(id: String) {
            require(ID_PATTERN.matches(id)) {
                "Invalid citadel-types identifier '$id'; expected lowercase [a-z0-9_-]+"
            }
        }
    }
}

data class CitadelTypeSettings(
    val item: Key? = null,
    val lvl: Int? = null,
    val health: HealthDefinition,
    val maturing: MaturingSettings? = null
) {
    init {
        require(lvl == null || lvl > 0) { "Citadel type lvl must be positive" }
        require(item == null || (item.namespace.isNotEmpty() && item.key.isNotEmpty())) {
            "Citadel type item must contain both namespace and value"
        }
        require(maturing == null || health.maxHealth != null) { "Maturing settings require health.max" }
    }
}

data class MaturingSettings(val durationSeconds: Long, val initialDamageMultiplier: Double) {
    init {
        require(durationSeconds >= 0) { "maturing.duration-seconds must be non-negative" }
        require(initialDamageMultiplier.isFinite() && initialDamageMultiplier >= 1.0) {
            "maturing.initial-damage-multiplier must be finite and at least 1"
        }
    }
}
