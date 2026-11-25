package ru.fornax.fornaxcitadel.paper.command

import org.bukkit.command.PluginCommand
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.paper.config.PaperMessages

internal class CitadelCommandRegistration(
    findCommand: (String) -> PluginCommand?,
    private val handler: CitadelCommand,
    messages: PaperMessages
) : LifecycleService {
    private val command = requireNotNull(findCommand("citadel")) { "The citadel command is missing from plugin.yml" }
    private var enabled = false

    init {
        command.description = messages.plainText("commands.description")
        command.usage = messages.plainText("commands.usage.help")
    }

    override fun enable() {
        if (enabled) return
        enabled = true
        command.setExecutor(handler)
        command.tabCompleter = handler
    }

    override fun disable() {
        if (!enabled) return
        if (command.executor === handler) command.setExecutor(null)
        if (command.tabCompleter === handler) command.tabCompleter = null
        enabled = false
    }
}
