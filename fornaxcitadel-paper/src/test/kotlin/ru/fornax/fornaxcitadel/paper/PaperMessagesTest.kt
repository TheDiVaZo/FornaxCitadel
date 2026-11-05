package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperMessagesTest {
    @Test
    fun `bundled language contains every gameplay and command message`() {
        val messages = PaperMessages.defaults()
        val gameplay = listOf(
            "placement.invalid-block", "placement.denied", "placement.success", "placement.error",
            "breaking.chunk-unavailable", "breaking.denied", "breaking.removed", "breaking.damaged", "breaking.error"
        )
        val commands = listOf(
            "description", "usage.help", "usage.save", "usage.place", "usage.remove", "usage.info", "usage.give",
            "help.info", "help.give", "help.admin", "help.types", "unknown-command", "no-permission", "error", "saved",
            "give.player-required", "give.invalid-amount", "give.missing-item", "give.unregistered-item",
            "give.success", "give.none", "give.inventory-full", "player-only", "invalid-target", "chunk-unavailable",
            "unknown-type", "place.success", "place.denied", "remove.success", "remove.denied",
            "info.not-reinforced", "info.type", "info.unknown-type", "info.health", "info.invulnerable"
        ).map { "commands.$it" }
        for (key in gameplay + commands) assertTrue(messages.plainText(key).isNotBlank(), key)
        assertSame(messages, PaperMessages.defaults())
        assertEquals("/citadel place <тип>", messages.plainText("commands.usage.place"))
        assertEquals("/citadel give <one|two> [игрок] [1–64]",
            messages.plainText("commands.usage.give", "types" to "one|two"))
        assertEquals("/citadel place <тип>, /citadel remove, /citadel save", messages.plainText("commands.help.admin"))
    }

    @Test
    fun `MiniMessage and ampersand legacy templates preserve their formatting`() {
        for ((format, template) in listOf(
            "minimessage" to "<red>Тип: {type}</red>",
            "LeGaCy" to "&cТип: {type}"
        )) {
            val messages = PaperMessages.read(YamlConfiguration().apply {
                set("format", format)
                set("commands.info.type", template)
            })
            assertEquals("Тип: камень", messages.plainText("commands.info.type", "type" to "камень"))
            assertEquals(NamedTextColor.RED, messages.render("commands.info.type", "type" to "камень").color())
        }
    }

    @Test
    fun `parameter text is neither formatted nor recursively substituted in either format`() {
        for ((format, template) in listOf(
            "MINIMESSAGE" to "<green>{type} | {player} | {type} | {unknown}</green>",
            "LEGACY" to "&a{type} | {player} | {type} | {unknown}"
        )) {
            val messages = PaperMessages.read(YamlConfiguration().apply {
                set("format", format)
                set("commands.info.type", template)
            })
            val parameters = arrayOf("type" to "<red>&c{player}", "player" to "Alex")
            assertEquals("<red>&c{player} | Alex | <red>&c{player} | {unknown}",
                messages.plainText("commands.info.type", *parameters))
            assertEquals(NamedTextColor.GREEN, messages.render("commands.info.type", *parameters).color())
        }
    }

    @Test
    fun `missing text keeps bundled format while independently overriding the channel`() {
        val messages = PaperMessages.read(YamlConfiguration().apply {
            set("format", "LEGACY")
            set("placement.success.channel", "cHaT")
            set("commands.info.type", "&bВид: {type}")
        })
        assertEquals(PaperMessages.defaults().render("placement.success"), messages.render("placement.success"))
        assertEquals(PaperMessages.defaults().render("commands.no-permission"), messages.render("commands.no-permission"))
        assertEquals(NamedTextColor.AQUA, messages.render("commands.info.type", "type" to "stone").color())
        val delivery = mutableListOf<String>()
        val player = recipient(delivery)
        messages.sendGameplay(player, "placement.success")
        messages.sendGameplay(player, "breaking.denied")
        assertEquals(listOf("sendMessage", "sendActionBar"), delivery)
    }

    @Test
    fun `NONE skips both player delivery methods and command responses still use chat`() {
        val messages = PaperMessages.read(YamlConfiguration().apply {
            set("breaking.damaged.channel", "none")
        })
        val delivery = mutableListOf<String>()
        val player = recipient(delivery)
        messages.sendGameplay(player, "breaking.damaged")
        assertTrue(delivery.isEmpty())
        messages.sendCommand(player, "commands.no-permission")
        assertEquals(listOf("sendMessage"), delivery)
    }

    @Test
    fun `empty configured text is preserved instead of being replaced with a default`() {
        val messages = PaperMessages.read(YamlConfiguration().apply {
            set("placement.success.text", "")
        })
        assertEquals(Component.empty(), messages.render("placement.success"))
    }

    @Test
    fun `invalid formats channels fields and ancestor types identify their paths`() {
        val invalid = listOf(
            "format" to 1,
            "format" to "PLAIN",
            "placement" to "bad",
            "placement.success" to listOf("bad"),
            "placement.success.text" to 12,
            "placement.success.channel" to false,
            "breaking.denied.channel" to "TITLE",
            "commands" to "bad",
            "commands.help" to listOf("bad"),
            "commands.info.health" to true,
            "commands.info.type" to mapOf("text" to "bad"),
            "commands.usage" to 12
        )
        for ((path, value) in invalid) {
            val failure = assertFailsWith<IllegalArgumentException>(path) {
                PaperMessages.read(YamlConfiguration().apply { set(path, value) })
            }
            assertTrue(failure.message.orEmpty().contains(path), "$path: ${failure.message}")
        }
    }

    @Test
    fun `loading creates UTF8 language file once preserves edits and rejects invalid YAML`() {
        val directory = Files.createTempDirectory("fornax-language-test").toFile()
        val file = directory.resolve("lang.yml")
        var saves = 0
        val plugin = paperProxy<Plugin>("language plugin") { method, args ->
            when (method.name) {
                "getDataFolder" -> directory
                "saveResource" -> {
                    assertEquals("lang.yml", args[0])
                    assertEquals(false, args[1])
                    saves++
                    checkNotNull(javaClass.getResourceAsStream("/lang.yml")).use { source ->
                        file.outputStream().use(source::copyTo)
                    }
                    null
                }
                else -> unsupported(method)
            }
        }
        try {
            assertEquals("Укрепление установлено.", PaperMessages.load(plugin).plainText("placement.success"))
            assertEquals(1, saves)
            val customized = "format: LEGACY\ncommands:\n  saved: '&aСохранено вручную.'\n"
            file.writeText(customized, Charsets.UTF_8)
            assertEquals("Сохранено вручную.", PaperMessages.load(plugin).plainText("commands.saved"))
            assertEquals(customized, file.readText(Charsets.UTF_8))
            assertEquals(1, saves)
            file.writeText("commands: [", Charsets.UTF_8)
            assertFailsWith<InvalidConfigurationException> { PaperMessages.load(plugin) }
            assertEquals(1, saves)
        } finally {
            Files.deleteIfExists(file.toPath())
            Files.deleteIfExists(directory.toPath())
        }
    }

    private fun recipient(delivery: MutableList<String>): Player = paperProxy("language recipient") { method, _ ->
        when (method.name) {
            "sendMessage", "sendActionBar" -> { delivery.add(method.name); null }
            else -> unsupported(method)
        }
    }
}
