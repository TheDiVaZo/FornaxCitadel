package ru.fornax.fornaxcitadel.paper

import org.bukkit.FluidCollisionMode
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.facade.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.NamedActionSource
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.supportsCitadelData
import java.util.Locale
import java.util.logging.Level

class CitadelCommand(
    private val plugin: Plugin,
    private val manager: ComponentManager,
    private val module: CitadelModule,
    private val materials: MaterialRegistry,
    private val citadelTypes: PaperCitadelTypes,
    private val messages: PaperMessages,
    private val save: () -> Unit
) : CommandExecutor, TabCompleter {
    constructor(
        plugin: Plugin,
        manager: ComponentManager,
        module: CitadelModule,
        materials: MaterialRegistry,
        citadelTypes: PaperCitadelTypes,
        save: () -> Unit
    ) : this(plugin, manager, module, materials, citadelTypes, PaperMessages.defaults(), save)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        try {
            val operation = args.firstOrNull()?.lowercase(Locale.ROOT)
            if (operation == null || operation == "help") {
                sender.message("commands.help.info")
                if (sender.hasPermission(ADMIN)) {
                    sender.message("commands.help.give")
                    sender.message("commands.help.admin")
                    sender.message("commands.help.types", "types" to citadelTypes.names.joinToString())
                }
                return true
            }
            if (operation !in OPERATIONS) {
                sender.message("commands.unknown-command")
                return true
            }
            val permission = if (operation == "info") "fornaxcitadel.info" else ADMIN
            if (!sender.hasPermission(permission)) {
                sender.message("commands.no-permission")
                return true
            }
            when (operation) {
                "give" -> give(sender, args)
                "save" -> {
                    if (args.size != 1) return usage(sender, "save")
                    save()
                    sender.message("commands.saved")
                }
                "place", "remove", "info" -> target(sender, operation, args)
            }
        } catch (_: CitadelResolutionUnavailableException) {
            sender.message("commands.chunk-unavailable")
        } catch (exception: Exception) {
            plugin.logger.log(Level.SEVERE, "Citadel command failed", exception)
            sender.message("commands.error")
        }
        return true
    }

    private fun give(sender: CommandSender, args: Array<out String>) {
        val type = args.getOrNull(1)?.let(citadelTypes::find)
        if (type == null || args.size !in 2..4) {
            usage(sender, "give", "types" to citadelTypes.names.joinToString("|"))
            return
        }
        val recipient = args.getOrNull(2)?.let(plugin.server::getPlayerExact) ?: if (args.size == 2) sender as? Player else null
        if (recipient == null) {
            sender.message("commands.give.player-required")
            return
        }
        val amount = if (args.size == 4) args[3].toIntOrNull() else 1
        if (amount == null || amount !in 1..64) {
            sender.message("commands.give.invalid-amount")
            return
        }
        val itemKey = citadelTypes.itemKey(type)
        if (itemKey == null) {
            sender.message("commands.give.missing-item", "type" to (citadelTypes.name(type) ?: type.asString()))
            return
        }
        val material = materials.getType(itemKey)
        if (material == null) {
            sender.message("commands.give.unregistered-item", "item" to itemKey.asString())
            return
        }
        val remaining = recipient.inventory.addItem(material.createItemStack(amount)).values.sumOf { it.amount }
        val given = amount - remaining
        sender.message(
            if (given == 0) "commands.give.none" else "commands.give.success",
            "player" to recipient.name, "given" to given.toString(), "amount" to amount.toString()
        )
        if (remaining > 0) sender.message("commands.give.inventory-full")
    }

    private fun target(sender: CommandSender, operation: String, args: Array<out String>) {
        val player = sender as? Player ?: run {
            sender.message("commands.player-only")
            return
        }
        if (args.size != if (operation == "place") 2 else 1) {
            usage(sender, operation)
            return
        }
        val block = player.getTargetBlockExact(6, FluidCollisionMode.NEVER)
        if (block == null || !block.supportsCitadelData()) {
            sender.message("commands.invalid-target")
            return
        }
        val position = CitadelPosition(block.world.uid, block.x, block.y, block.z)
        if (!manager.isChunkLoaded(position.world, position.x shr 4, position.z shr 4)) {
            sender.message("commands.chunk-unavailable")
            return
        }
        val context = ActionContext(player.uniqueId, COMMAND_SOURCE)
        when (operation) {
            "place" -> {
                val type = citadelTypes.find(args[1])
                if (type == null) {
                    sender.message("commands.unknown-type", "type" to args[1])
                    return
                }
                val result = module.actions.execute(PlaceAction(context, position, type))
                sender.message(if (result == ActionResult.SUCCESS) "commands.place.success" else "commands.place.denied")
            }
            "remove" -> {
                val result = module.actions.execute(RemoveAction(context, position))
                sender.message(if (result == ActionResult.SUCCESS) "commands.remove.success" else "commands.remove.denied")
            }
            "info" -> {
                val citadel = module.citadels.findProtecting(position)
                if (citadel == null) {
                    sender.message("commands.info.not-reinforced")
                    return
                }
                val kind = citadel.getComponent(CitadelKind::class.java)?.definitionKey
                val type = kind?.let(citadelTypes::name) ?: kind?.asString()
                    ?: messages.plainText("commands.info.unknown-type")
                sender.message("commands.info.type", "type" to type)
                val health = citadel.getComponent(Health::class.java)
                if (health != null) {
                    sender.message("commands.info.health",
                        "health" to String.format(Locale.ROOT, "%.1f", health.health),
                        "maximum" to String.format(Locale.ROOT, "%.1f", health.maxHealth))
                }
                if (kind != null && citadelTypes.isInvulnerable(kind)) sender.message("commands.info.invulnerable")
            }
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val candidates = when {
            args.size == 1 -> if (sender.hasPermission(ADMIN)) OPERATIONS else listOf("info", "help")
            !sender.hasPermission(ADMIN) -> emptyList()
            args.size == 2 && args[0].lowercase(Locale.ROOT) in listOf("give", "place") -> citadelTypes.names
            args.size == 3 && args[0].equals("give", ignoreCase = true) -> plugin.server.onlinePlayers.map { it.name }
            args.size == 4 && args[0].equals("give", ignoreCase = true) -> listOf("1", "16", "64")
            else -> emptyList()
        }
        return candidates.filter { it.startsWith(args.lastOrNull().orEmpty(), ignoreCase = true) }
    }

    private fun usage(sender: CommandSender, operation: String, vararg parameters: Pair<String, String>): Boolean {
        sender.message("commands.usage.$operation", *parameters)
        return true
    }

    private fun CommandSender.message(key: String, vararg parameters: Pair<String, String>) {
        messages.sendCommand(this, key, *parameters)
    }

    private companion object {
        const val ADMIN = "fornaxcitadel.admin"
        val COMMAND_SOURCE = NamedActionSource(Key("fornaxcitadel", "paper_command"))
        val OPERATIONS = listOf("give", "place", "remove", "info", "save", "help")
    }
}
