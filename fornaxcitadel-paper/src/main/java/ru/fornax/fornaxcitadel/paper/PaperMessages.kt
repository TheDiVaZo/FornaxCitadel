package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.Locale
import java.util.regex.Pattern

/** Immutable language templates, with independently configurable delivery for gameplay feedback. */
class PaperMessages private constructor(
    private val templates: Map<String, Component>,
    private val channels: Map<String, Channel>
) {
    fun render(key: String, vararg parameters: Pair<String, String>): Component {
        val template = requireNotNull(templates[key]) { "Unknown language message: $key" }
        if (parameters.isEmpty()) return template
        val values = parameters.toMap()
        return template.replaceText(TextReplacementConfig.builder()
            .match(PLACEHOLDER)
            .replacement { match, builder -> values[match.group(1)]?.let(Component::text) ?: builder }
            .build())
    }

    fun plainText(key: String, vararg parameters: Pair<String, String>): String =
        PlainTextComponentSerializer.plainText().serialize(render(key, *parameters))

    fun sendGameplay(player: Player, key: String, vararg parameters: Pair<String, String>) {
        when (requireNotNull(channels[key]) { "Not a gameplay message: $key" }) {
            Channel.ACTION_BAR -> player.sendActionBar(render(key, *parameters))
            Channel.CHAT -> player.sendMessage(render(key, *parameters))
            Channel.NONE -> Unit
        }
    }

    fun sendCommand(sender: CommandSender, key: String, vararg parameters: Pair<String, String>) {
        sender.sendMessage(render(key, *parameters))
    }

    private enum class Channel { ACTION_BAR, CHAT, NONE }
    private enum class Format { MINIMESSAGE, LEGACY }

    companion object {
        private val PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_-]+)\\}")
        private val bundled: PaperMessages by lazy {
            val configuration = YamlConfiguration()
            requireNotNull(PaperMessages::class.java.getResourceAsStream("/lang.yml")) {
                "The bundled lang.yml is missing"
            }.bufferedReader(Charsets.UTF_8).use(configuration::load)
            val format = format(value(configuration, "format"))
            val templates = linkedMapOf<String, Component>()
            val channels = linkedMapOf<String, Channel>()
            for (group in listOf("placement", "breaking")) {
                val section = requireNotNull(configuration.getConfigurationSection(group)) {
                    "$group must be a mapping"
                }
                for (name in section.getKeys(false)) {
                    val key = "$group.$name"
                    templates[key] = parse(requiredString(configuration, "$key.text"), format)
                    channels[key] = channel(value(configuration, "$key.channel"), "$key.channel")
                }
            }
            fun commandTemplates(section: ConfigurationSection, prefix: String) {
                for (name in section.getKeys(false)) {
                    val key = "$prefix.$name"
                    val entry = section.get(name, null)
                    if (entry is ConfigurationSection) commandTemplates(entry, key)
                    else templates[key] = parse(string(entry, key), format)
                }
            }
            commandTemplates(requireNotNull(configuration.getConfigurationSection("commands")) {
                "commands must be a mapping"
            }, "commands")
            PaperMessages(templates, channels)
        }

        @JvmStatic
        fun defaults(): PaperMessages = bundled

        /** Missing entries retain already parsed bundled text, even when the custom format differs. */
        @JvmStatic
        fun read(config: ConfigurationSection): PaperMessages {
            val defaults = defaults()
            val format = format(value(config, "format") ?: Format.MINIMESSAGE.name)
            val templates = defaults.templates.mapValues { (key, fallback) ->
                val path = if (key in defaults.channels) "$key.text" else key
                value(config, path)?.let { parse(string(it, path), format) } ?: fallback
            }
            val channels = defaults.channels.mapValues { (key, fallback) ->
                val path = "$key.channel"
                value(config, path)?.let { channel(it, path) } ?: fallback
            }
            return PaperMessages(templates, channels)
        }

        @JvmStatic
        fun load(plugin: Plugin): PaperMessages {
            val file = File(plugin.dataFolder, "lang.yml")
            if (!file.exists()) plugin.saveResource("lang.yml", false)
            val configuration = YamlConfiguration()
            file.bufferedReader(Charsets.UTF_8).use(configuration::load)
            return read(configuration)
        }

        private fun parse(text: String, format: Format): Component = when (format) {
            Format.MINIMESSAGE -> MiniMessage.miniMessage().deserialize(text)
            Format.LEGACY -> LegacyComponentSerializer.legacyAmpersand().deserialize(text)
        }

        private fun format(value: Any?): Format {
            val text = string(value, "format")
            return Format.entries.firstOrNull { it.name == text.uppercase(Locale.ROOT) }
                ?: throw IllegalArgumentException("format must be MINIMESSAGE or LEGACY: $text")
        }

        private fun channel(value: Any?, path: String): Channel {
            val text = string(value, path)
            return Channel.entries.firstOrNull { it.name == text.uppercase(Locale.ROOT) }
                ?: throw IllegalArgumentException("$path must be ACTION_BAR, CHAT or NONE: $text")
        }

        private fun requiredString(config: ConfigurationSection, path: String): String = string(value(config, path), path)

        private fun string(value: Any?, path: String): String {
            require(value is String) { "$path must be a string" }
            return value
        }

        /** Read local values and validate every ancestor instead of treating malformed groups as absent. */
        private fun value(config: ConfigurationSection, path: String): Any? {
            var current: Any? = config
            var prefix = ""
            for (part in path.split('.')) {
                if (current == null) return null
                current = when (val parent = current) {
                    is ConfigurationSection -> if (parent.contains(part, true)) parent.get(part, null) else null
                    is Map<*, *> -> parent[part]
                    else -> throw IllegalArgumentException("$prefix must be a mapping")
                }
                prefix = if (prefix.isEmpty()) part else "$prefix.$part"
            }
            return current
        }
    }
}
