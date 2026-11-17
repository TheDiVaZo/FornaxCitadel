package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.configuration.ConfigurationSection
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.config.exactInt
import ru.fornax.fornaxcitadel.paper.config.exactLong
import ru.fornax.fornaxcitadel.paper.config.readLocalMapping
import java.util.Locale

data class CitadelTypesSettings(val types: Map<String, CitadelTypeSettings> = DEFAULT_TYPES) {
    init {
        val itemOwners = mutableMapOf<Key, String>()
        types.forEach { (id, settings) ->
            requireValidId(id)
            val previous = itemOwners.putIfAbsent(settings.item, id)
            require(previous == null) {
                "citadel-types.$id.item duplicates citadel-types.$previous.item: ${settings.item}"
            }
        }
    }

    companion object {
        val DEFAULT_TYPES: Map<String, CitadelTypeSettings> = linkedMapOf(
            "reinforced_light" to CitadelTypeSettings(Key("minecraft", "copper_ingot"), lvl = 1,
                health = HealthSettings(10.0)),
            "reinforced" to CitadelTypeSettings(Key("minecraft", "iron_ingot"), lvl = 2,
                health = HealthSettings(20.0)),
            "reinforced_heavy" to CitadelTypeSettings(Key("minecraft", "diamond"), lvl = 3,
                health = HealthSettings(30.0)),
            "maturing_fast" to CitadelTypeSettings(Key("minecraft", "amethyst_shard"), lvl = 2,
                health = HealthSettings(20.0), maturing = MaturingSettings(300, 2.0)),
            "maturing" to CitadelTypeSettings(Key("minecraft", "emerald"), lvl = 4,
                health = HealthSettings(40.0), maturing = MaturingSettings(600, 4.0)),
            "maturing_strong" to CitadelTypeSettings(Key("minecraft", "netherite_ingot"), lvl = 5,
                health = HealthSettings(80.0), maturing = MaturingSettings(1800, 4.0)),
            "invulnerable" to CitadelTypeSettings(Key("minecraft", "nether_star"), lvl = 999,
                damagePolicy = CitadelDamagePolicy.DENY)
        )
        private val ID_PATTERN = Regex("[a-z0-9_-]+")

        fun read(root: ConfigurationSection): CitadelTypesSettings {
            // A user catalog replaces the bundled catalog, including when the user leaves it empty.
            val configured = root.get("citadel-types", null) ?: return CitadelTypesSettings()
            val types = readLocalMapping(configured, "citadel-types").mapValues { (id, value) ->
                requireValidId(id)
                readType(value, "citadel-types.$id")
            }
            return CitadelTypesSettings(types)
        }

        private fun requireValidId(id: String) {
            require(ID_PATTERN.matches(id)) {
                "Invalid citadel-types identifier '$id'; expected lowercase [a-z0-9_-]+"
            }
        }

        private fun readType(value: Any?, path: String): CitadelTypeSettings {
            val values = readLocalMapping(value, path)
            val unknown = values.keys.firstOrNull { it !in setOf("item", "lvl", "damage-policy", "health", "maturing") }
            require(unknown == null) { "Unknown configuration field $path.$unknown" }
            val item = readItem(values["item"], "$path.item")
            val lvl = readLevel(values["lvl"], "$path.lvl")
            val rawPolicy = if (values.containsKey("damage-policy")) values["damage-policy"] else "allow"
            require(rawPolicy is String) { "$path.damage-policy must be allow or deny" }
            val policy = when (rawPolicy.lowercase(Locale.ROOT)) {
                "allow" -> CitadelDamagePolicy.ALLOW
                "deny" -> CitadelDamagePolicy.DENY
                else -> throw IllegalArgumentException("Unknown $path.damage-policy: $rawPolicy; expected allow or deny")
            }
            val health = if (values.containsKey("health")) readHealth(values["health"], "$path.health") else null
            require(policy != CitadelDamagePolicy.ALLOW || health != null) {
                "$path.health is required for damage-policy allow"
            }
            val maturing = if (values.containsKey("maturing")) readMaturing(values["maturing"], "$path.maturing") else null
            require(maturing == null || health != null) { "$path.maturing requires $path.health" }
            return CitadelTypeSettings(item, lvl, policy, health, maturing)
        }

        private fun readItem(value: Any?, path: String): Key {
            require(value is String && value.isNotEmpty()) {
                "$path is required and must be a lowercase item key (item or namespace:item)"
            }
            val separator = value.indexOf(':')
            val namespace = if (separator < 0) "minecraft" else value.substring(0, separator)
            val item = if (separator < 0) value else value.substring(separator + 1)
            require(namespace.isNotEmpty() && item.isNotEmpty() &&
                Key.parseableNamespace(namespace) && Key.parseableValue(item)) {
                "Invalid $path: $value; expected a lowercase item key (item or namespace:item)"
            }
            return Key(namespace, item)
        }

        private fun readLevel(value: Any?, path: String): Int {
            val message = "$path is required and must be a positive whole number fitting in Int"
            require(value is Number) { message }
            val lvl = try {
                value.exactInt()
            } catch (exception: NumberFormatException) {
                throw IllegalArgumentException(message, exception)
            } catch (exception: ArithmeticException) {
                throw IllegalArgumentException(message, exception)
            }
            require(lvl > 0) { message }
            return lvl
        }

        private fun readHealth(value: Any?, path: String): HealthSettings {
            val values = readLocalMapping(value, path)
            val unknown = values.keys.firstOrNull { it != "max" }
            require(unknown == null) { "Unknown configuration field $path.$unknown" }
            return HealthSettings(readPositive(values["max"], "$path.max"))
        }

        private fun readMaturing(value: Any?, path: String): MaturingSettings {
            val values = readLocalMapping(value, path)
            val unknown = values.keys.firstOrNull { it !in setOf("duration-seconds", "initial-damage-multiplier") }
            require(unknown == null) { "Unknown configuration field $path.$unknown" }
            val multiplier = readPositive(values["initial-damage-multiplier"], "$path.initial-damage-multiplier")
            require(multiplier >= 1.0) { "$path.initial-damage-multiplier must be at least 1" }
            return MaturingSettings(readDuration(values["duration-seconds"], "$path.duration-seconds"), multiplier)
        }

        private fun readPositive(value: Any?, path: String): Double {
            require(value is Number) { "$path must be a positive finite number" }
            return value.toDouble().also { number ->
                require(number.isFinite() && number > 0.0) { "$path must be a positive finite number" }
            }
        }

        private fun readDuration(value: Any?, path: String): Long {
            require(value is Number) { "$path must be a non-negative whole number of seconds" }
            val seconds = try {
                value.exactLong()
            } catch (exception: NumberFormatException) {
                throw IllegalArgumentException("$path must be a non-negative whole number of seconds", exception)
            } catch (exception: ArithmeticException) {
                throw IllegalArgumentException("$path must be a non-negative whole number of seconds fitting in Long", exception)
            }
            require(seconds >= 0) { "$path must be non-negative" }
            return seconds
        }
    }
}

data class CitadelTypeSettings(
    val item: Key,
    val lvl: Int,
    val damagePolicy: CitadelDamagePolicy = CitadelDamagePolicy.ALLOW,
    val health: HealthSettings? = null,
    val maturing: MaturingSettings? = null
) {
    init {
        require(lvl > 0) { "Citadel type lvl must be positive" }
        require(item.namespace.isNotEmpty() && item.key.isNotEmpty()) {
            "Citadel type item must contain both namespace and value"
        }
        require(damagePolicy != CitadelDamagePolicy.ALLOW || health != null) {
            "Health settings are required for damage-policy allow"
        }
        require(maturing == null || health != null) { "Maturing settings require health" }
    }
}

enum class CitadelDamagePolicy { ALLOW, DENY }

data class HealthSettings(val max: Double) {
    init {
        require(max.isFinite() && max > 0.0) { "health.max must be positive and finite" }
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
