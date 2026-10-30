package ru.fornax.fornaxcitadel.paper

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
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

    private object TestCommand : Command("citadel") {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean =
            error("Only the registered command executor is exercised")
    }
}
