package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CitadelCommandTest {
    @Test
    fun `admin commands reject unauthorized callers before target lookup or save`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.installer.target = fixture.block()
        var saves = 0
        val commands = CitadelCommand(fixture.plugin, fixture.manager, fixture.module, fixture.materials, fixture.citadelTypes) { saves++ }

        commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("place", "invulnerable"))
        commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("save"))

        assertEquals(0, fixture.installer.targetLookups)
        assertEquals(0, saves)
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
    }

    @Test
    fun `admin target placement and removal go through core and leave the block intact`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.installer.permissions.add("fornaxcitadel.admin")
        fixture.installer.target = fixture.block()
        val commands = CitadelCommand(fixture.plugin, fixture.manager, fixture.module, fixture.materials, fixture.citadelTypes) {}

        commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("place", "invulnerable"))

        val placed = assertNotNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(PaperCitadelTypes.INVULNERABLE, placed.getComponent(CitadelKind::class.java)?.definitionKey)
        commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("remove"))
        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(0, fixture.vanillaBlockChanges)
    }

    @Test
    fun `missing target and invalid give quantity do not mutate state`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.installer.permissions.add("fornaxcitadel.admin")
        val commands = CitadelCommand(fixture.plugin, fixture.manager, fixture.module, fixture.materials, fixture.citadelTypes) {}

        commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("place", "invulnerable"))
        commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("give", "invulnerable", "installer", "0"))

        assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
        assertEquals(0, fixture.installer.held.amount)
        assertTrue(fixture.installer.messages.isNotEmpty())
    }

    @Test
    fun `custom help and usage remain in chat for ordinary players and administrators`() {
        PaperTestFixture(permissions = true).use { fixture ->
            val messages = PaperMessages.read(YamlConfiguration().apply {
                set("commands.help.info", "Inspect help")
                set("commands.help.give", "Give help")
                set("commands.help.admin", "Admin help")
                set("commands.help.types", "Available: {types}")
                for (operation in listOf("info", "place", "remove", "save")) {
                    set("commands.usage.$operation", "Usage $operation")
                }
                set("commands.usage.give", "Usage give {types}")
            })
            val commands = CitadelCommand(fixture.plugin, fixture.manager, fixture.module,
                fixture.materials, fixture.citadelTypes, messages) {}

            commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("help"))
            assertEquals(listOf("Inspect help"), fixture.installer.chatMessages.plainText())

            fixture.installer.permissions.addAll(listOf("fornaxcitadel.admin", "fornaxcitadel.info"))
            commands.onCommand(fixture.installer.player, TestCommand, "citadel", emptyArray())
            for (operation in listOf("info", "place", "remove", "save")) {
                commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf(operation, "extra", "extra"))
            }
            commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("give"))

            assertEquals(listOf(
                "Inspect help", "Inspect help", "Give help", "Admin help",
                "Available: reinforced, maturing, invulnerable",
                "Usage info", "Usage place", "Usage remove", "Usage save",
                "Usage give reinforced|maturing|invulnerable"
            ), fixture.installer.chatMessages.plainText())
            assertTrue(fixture.installer.actionBarMessages.isEmpty())
        }
    }

    @Test
    fun `custom info and give messages substitute values and retain numeric formatting`() {
        PaperTestFixture(permissions = true).use { fixture ->
            fixture.installer.permissions.addAll(listOf("fornaxcitadel.admin", "fornaxcitadel.info"))
            fixture.installer.target = fixture.block()
            fixture.place()
            val messages = PaperMessages.read(YamlConfiguration().apply {
                set("commands.info.type", "Kind={type}")
                set("commands.info.health", "HP={health};max={maximum}")
                set("commands.give.success", "Recipient={player};given={given};requested={amount}")
            })
            val itemKey = assertNotNull(fixture.citadelTypes.itemKey(PaperCitadelTypes.REINFORCED))
            val materials = TestMaterialRegistry(TestMaterialAdapter(itemKey))
            val commands = CitadelCommand(fixture.plugin, fixture.manager, fixture.module,
                materials, fixture.citadelTypes, messages) {}

            commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("info"))
            commands.onCommand(fixture.installer.player, TestCommand, "citadel",
                arrayOf("give", "reinforced", "stranger", "3"))

            assertEquals(listOf(
                "Kind=reinforced", "HP=100.0;max=100.0", "Recipient=stranger;given=3;requested=3"
            ), fixture.installer.chatMessages.plainText())
            assertEquals(3, fixture.stranger.addedItems.single().amount)
            assertTrue(fixture.installer.actionBarMessages.isEmpty())
        }
    }

    @Test
    fun `unknown type arguments stay literal in both configured text formats`() {
        for ((format, template) in listOf(
            "MINIMESSAGE" to "<yellow>Unknown {type}</yellow>",
            "LEGACY" to "&eUnknown {type}"
        )) {
            PaperTestFixture(permissions = true).use { fixture ->
                fixture.installer.permissions.add("fornaxcitadel.admin")
                fixture.installer.target = fixture.block()
                val messages = PaperMessages.read(YamlConfiguration().apply {
                    set("format", format)
                    set("commands.unknown-type", template)
                })
                val commands = CitadelCommand(fixture.plugin, fixture.manager, fixture.module,
                    fixture.materials, fixture.citadelTypes, messages) {}
                val argument = "<red>unregistered</red>&a"

                commands.onCommand(fixture.installer.player, TestCommand, "citadel", arrayOf("place", argument))

                assertEquals(listOf("Unknown $argument"), fixture.installer.chatMessages.plainText(), format)
                assertTrue(fixture.installer.actionBarMessages.isEmpty())
                assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
            }
        }
    }

    @Test
    fun `console receives configured command messages through chat`() {
        PaperTestFixture().use { fixture ->
            val received = mutableListOf<Any?>()
            val console = paperProxy<CommandSender>("console") { method, args ->
                when (method.name) {
                    "hasPermission" -> true
                    "sendMessage" -> { received.add(args.lastOrNull()); null }
                    else -> unsupported(method)
                }
            }
            val messages = PaperMessages.read(YamlConfiguration().apply {
                set("commands.saved", "Saved from console")
                set("commands.player-only", "Player required")
            })
            var saves = 0
            val commands = CitadelCommand(fixture.plugin, fixture.manager, fixture.module,
                fixture.materials, fixture.citadelTypes, messages) { saves++ }

            commands.onCommand(console, TestCommand, "citadel", arrayOf("save"))
            commands.onCommand(console, TestCommand, "citadel", arrayOf("info"))

            assertEquals(1, saves)
            assertEquals(listOf("Saved from console", "Player required"), received.plainText())
        }
    }

    private fun List<Any?>.plainText(): List<String> = filterIsInstance<Component>()
        .map(PlainTextComponentSerializer.plainText()::serialize)

    private object TestCommand : Command("citadel") {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean =
            error("Only the registered command executor is exercised")
    }
}
