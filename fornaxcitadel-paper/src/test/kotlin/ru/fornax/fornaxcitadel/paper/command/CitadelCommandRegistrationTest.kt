package ru.fornax.fornaxcitadel.paper.command

import org.bukkit.command.PluginCommand
import org.bukkit.command.PluginCommandYamlParser
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginDescriptionFile
import ru.fornax.fornaxcitadel.paper.PaperTestFixture
import ru.fornax.fornaxcitadel.paper.config.PaperMessages
import ru.fornax.fornaxcitadel.paper.paperProxy
import ru.fornax.fornaxcitadel.paper.unsupported
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class CitadelCommandRegistrationTest {
    @Test
    fun `construction localizes the command and the lifecycle controls both bindings`() {
        PaperTestFixture().use { fixture ->
            val command = pluginCommand()
            val messages = PaperMessages.read(YamlConfiguration().apply {
                set("commands.description", "Custom description")
                set("commands.usage.help", "/citadel custom")
            })
            val handler = fixture.command(messages = messages)
            val registration = CitadelCommandRegistration({ name -> command.takeIf { it.name == name } }, handler, messages)

            assertEquals("Custom description", command.description)
            assertEquals("/citadel custom", command.usage)
            assertSame(command.plugin, command.executor)
            assertNull(command.tabCompleter)

            repeat(2) { registration.enable() }
            assertSame(handler, command.executor)
            assertSame(handler, command.tabCompleter)

            repeat(2) { registration.disable() }
            assertSame(command.plugin, command.executor)
            assertNull(command.tabCompleter)
        }
    }

    @Test
    fun `disable preserves replacements independently for executor and tab completer`() {
        PaperTestFixture().use { fixture ->
            val command = pluginCommand()
            val registration = CitadelCommandRegistration({ command }, fixture.command(), PaperMessages.defaults())
            val external = fixture.command()

            registration.enable()
            command.setExecutor(external)
            registration.enable()
            registration.disable()
            assertSame(external, command.executor)
            assertNull(command.tabCompleter)

            registration.enable()
            command.tabCompleter = external
            registration.enable()
            registration.disable()
            assertSame(command.plugin, command.executor)
            assertSame(external, command.tabCompleter)
        }
    }

    @Test
    fun `missing citadel command fails at construction`() {
        PaperTestFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                CitadelCommandRegistration({ name ->
                    assertEquals("citadel", name)
                    null
                }, fixture.command(), PaperMessages.defaults())
            }
        }
    }

    private fun pluginCommand(): PluginCommand {
        val description = requireNotNull(javaClass.getResourceAsStream("/plugin.yml")).use { PluginDescriptionFile(it) }
        val owner = paperProxy<Plugin>("command owner") { method, _ ->
            if (method.name == "getDescription") description else unsupported(method)
        }
        return PluginCommandYamlParser.parse(owner).filterIsInstance<PluginCommand>().single { it.name == "citadel" }
    }
}
