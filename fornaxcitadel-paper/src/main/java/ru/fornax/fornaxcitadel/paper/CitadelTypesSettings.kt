package ru.fornax.fornaxcitadel.paper

import org.bukkit.configuration.ConfigurationSection
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.HealthBehavior
import ru.fornax.fornaxcitadel.modules.health.MaturingHealthBehavior
import ru.fornax.fornaxcitadel.modules.health.SimpleHealthBehavior
import java.math.BigDecimal
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
            "reinforced_light" to CitadelTypeSettings(Key("fornaxcitadel", "reinforced_light"),
                health = HealthBehaviorSettings.Simple(10.0)),
            "reinforced" to CitadelTypeSettings(Key("fornaxcitadel", "reinforced"),
                health = HealthBehaviorSettings.Simple(20.0)),
            "reinforced_heavy" to CitadelTypeSettings(Key("fornaxcitadel", "reinforced_heavy"),
                health = HealthBehaviorSettings.Simple(30.0)),
            "maturing_fast" to CitadelTypeSettings(Key("fornaxcitadel", "maturing_fast"),
                health = HealthBehaviorSettings.Maturing(10.0, 20.0, 300)),
            "maturing" to CitadelTypeSettings(Key("fornaxcitadel", "maturing"),
                health = HealthBehaviorSettings.Maturing(10.0, 40.0, 600)),
            "maturing_strong" to CitadelTypeSettings(Key("fornaxcitadel", "maturing_strong"),
                health = HealthBehaviorSettings.Maturing(20.0, 80.0, 1800)),
            "invulnerable" to CitadelTypeSettings(Key("fornaxcitadel", "invulnerable"), CitadelBreakPolicy.UNBREAKABLE)
        )
        private val ID_PATTERN = Regex("[a-z0-9_-]+")

        fun read(root: ConfigurationSection): CitadelTypesSettings {
            // A user catalog replaces the bundled catalog, including when the user leaves it empty.
            val configured = root.get("citadel-types", null) ?: return CitadelTypesSettings()
            val types = readMapping(configured, "citadel-types").mapValues { (id, value) ->
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
            val values = readMapping(value, path)
            val item = readItem(values["item"], "$path.item")
            val rawPolicy = if (values.containsKey("break-policy")) values["break-policy"] else "damage"
            require(rawPolicy is String) { "$path.break-policy must be damage or unbreakable" }
            val policy = when (rawPolicy.lowercase(Locale.ROOT)) {
                "damage" -> CitadelBreakPolicy.DAMAGE
                "unbreakable" -> CitadelBreakPolicy.UNBREAKABLE
                else -> throw IllegalArgumentException("Unknown $path.break-policy: $rawPolicy; expected damage or unbreakable")
            }
            val health = if (values.containsKey("health")) readHealth(values["health"], "$path.health") else null
            require(policy != CitadelBreakPolicy.DAMAGE || health != null) {
                "$path.health is required for break-policy damage"
            }
            return CitadelTypeSettings(item, policy, health)
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

        private fun readHealth(value: Any?, path: String): HealthBehaviorSettings {
            val values = readMapping(value, path)
            val behavior = values["behavior"]
            require(behavior is String) { "$path.behavior must be simple or maturing" }
            return when (behavior.lowercase(Locale.ROOT)) {
                "simple" -> HealthBehaviorSettings.Simple(readPositive(values["max"], "$path.max"))
                "maturing" -> {
                    val initial = readPositive(values["initial"], "$path.initial")
                    val mature = readPositive(values["mature"], "$path.mature")
                    require(mature >= initial) { "$path.mature must be at least $path.initial" }
                    HealthBehaviorSettings.Maturing(initial, mature,
                        readDuration(values["duration-seconds"], "$path.duration-seconds"))
                }
                else -> throw IllegalArgumentException("Unknown $path.behavior: $behavior; expected simple or maturing")
            }
        }

        private fun readMapping(value: Any?, path: String): Map<String, Any?> {
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

        private fun readPositive(value: Any?, path: String): Double {
            require(value is Number) { "$path must be a positive finite number" }
            return value.toDouble().also { number ->
                require(number.isFinite() && number > 0.0) { "$path must be a positive finite number" }
            }
        }

        private fun readDuration(value: Any?, path: String): Long {
            require(value is Number) { "$path must be a positive whole number of seconds" }
            val seconds = try {
                BigDecimal(value.toString()).longValueExact()
            } catch (exception: NumberFormatException) {
                throw IllegalArgumentException("$path must be a positive whole number of seconds", exception)
            } catch (exception: ArithmeticException) {
                throw IllegalArgumentException("$path must be a positive whole number of seconds fitting in Long", exception)
            }
            require(seconds > 0 && seconds <= Long.MAX_VALUE / 1000) {
                "$path must be positive and fit in milliseconds without overflow"
            }
            return seconds
        }
    }
}

data class CitadelTypeSettings(
    val item: Key,
    val breakPolicy: CitadelBreakPolicy = CitadelBreakPolicy.DAMAGE,
    val health: HealthBehaviorSettings? = null
) {
    init {
        require(item.namespace.isNotEmpty() && item.key.isNotEmpty()) {
            "Citadel type item must contain both namespace and value"
        }
        require(breakPolicy != CitadelBreakPolicy.DAMAGE || health != null) {
            "Health settings are required for break-policy damage"
        }
    }
}

enum class CitadelBreakPolicy { DAMAGE, UNBREAKABLE }

sealed interface HealthBehaviorSettings {
    fun createBehavior(): HealthBehavior

    data class Simple(val max: Double) : HealthBehaviorSettings {
        init {
            require(max.isFinite() && max > 0.0) { "health.max must be positive and finite" }
        }

        override fun createBehavior(): HealthBehavior = SimpleHealthBehavior(max)
    }

    data class Maturing(val initial: Double, val mature: Double, val durationSeconds: Long) : HealthBehaviorSettings {
        init {
            require(initial.isFinite() && initial > 0.0) { "health.initial must be positive and finite" }
            require(mature.isFinite() && mature >= initial) { "health.mature must be finite and at least health.initial" }
            require(durationSeconds > 0 && durationSeconds <= Long.MAX_VALUE / 1000) {
                "health.duration-seconds must be positive and fit in milliseconds without overflow"
            }
        }

        override fun createBehavior(): HealthBehavior =
            MaturingHealthBehavior(initial, mature, Math.multiplyExact(durationSeconds, 1000))
    }
}
