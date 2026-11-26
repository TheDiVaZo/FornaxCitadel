package ru.fornax.fornaxcitadel.paper.config

import org.bukkit.configuration.ConfigurationSection
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.HealthDefinition
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypeSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.MaturingSettings
import java.util.Locale

object CitadelTypesSettingsReader {
    fun read(config: ConfigurationSection): CitadelTypesSettings {
        // A user catalog replaces the bundled catalog, including when the user leaves it empty.
        val configured = config.get("citadel-types", null) ?: return CitadelTypesSettings()
        val types = readLocalMapping(configured, "citadel-types").mapValues { (id, value) ->
            CitadelTypesSettings.requireValidId(id)
            readType(value, "citadel-types.$id")
        }
        return CitadelTypesSettings(types)
    }

    private fun readType(value: Any?, path: String): CitadelTypeSettings {
        val values = readLocalMapping(value, path)
        val unknown = values.keys.firstOrNull { it !in setOf("item", "lvl", "damage-policy", "health", "maturing") }
        require(unknown == null) { "Unknown configuration field $path.$unknown" }
        val item = values["item"]?.let { readItem(it, "$path.item") }
        val lvl = values["lvl"]?.let { readLevel(it, "$path.lvl") }
        val legacyPolicy = if ("damage-policy" in values) {
            when ((values["damage-policy"] as? String)?.lowercase(Locale.ROOT)) {
                "allow" -> false
                "deny" -> true
                else -> throw IllegalArgumentException("$path.damage-policy must be allow or deny")
            }
        } else null
        val health = readHealth(values["health"], "$path.health", legacyPolicy)
        val maturing = if (values.containsKey("maturing")) readMaturing(values["maturing"], "$path.maturing") else null
        require(maturing == null || health.maxHealth != null) { "$path.maturing requires $path.health.max" }
        return CitadelTypeSettings(item, lvl, health, maturing)
    }

    private fun readItem(value: Any?, path: String): Key {
        require(value is String && value.isNotEmpty()) {
            "$path must be a lowercase item key (item or namespace:item)"
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
        val message = "$path must be a positive whole number fitting in Int"
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

    private fun readHealth(value: Any?, path: String, legacyPolicy: Boolean?): HealthDefinition {
        val values = if (value == null) emptyMap() else readLocalMapping(value, path)
        val unknown = values.keys.firstOrNull { it != "max" && it != "invulnerable" }
        require(unknown == null) { "Unknown configuration field $path.$unknown" }
        val invulnerable = if ("invulnerable" in values) {
            val configured = values["invulnerable"]
            require(configured is Boolean) { "$path.invulnerable must be a boolean" }
            require(legacyPolicy == null || legacyPolicy == configured) { "$path.invulnerable conflicts with damage-policy" }
            configured
        } else legacyPolicy ?: false
        val maximum = values["max"]?.let { readPositive(it, "$path.max") }
        require(invulnerable || maximum != null) { "$path.max is required for a damageable citadel" }
        return HealthDefinition(maximum, invulnerable)
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
