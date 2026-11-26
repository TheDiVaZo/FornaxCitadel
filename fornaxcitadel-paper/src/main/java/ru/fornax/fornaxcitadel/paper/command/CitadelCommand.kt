package ru.fornax.fornaxcitadel.paper.command

import org.bukkit.FluidCollisionMode
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.config.PaperMessages
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidActivationRequest
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidFailure
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidResult
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidService
import ru.fornax.fornaxcitadel.paper.modules.acid.acidPlayerContext
import ru.fornax.fornaxcitadel.paper.modules.breaking.citadelPosition
import ru.fornax.fornaxcitadel.paper.modules.supportsCitadelData
import java.util.Locale
import java.util.logging.Level

class CitadelCommand(
    private val plugin: Plugin,
    private val actions: ActionFacade,
    private val citadels: CitadelFacade,
    private val health: HealthService,
    private val materials: MaterialRegistry,
    private val definitions: CitadelDefinitionRegistry,
    private val messages: PaperMessages,
    private val acid: AcidService? = null,
    private val save: () -> Unit
) : CommandExecutor, TabCompleter {
    constructor(
        plugin: Plugin,
        actions: ActionFacade,
        citadels: CitadelFacade,
        health: HealthService,
        materials: MaterialRegistry,
        definitions: CitadelDefinitionRegistry,
        save: () -> Unit
    ) : this(plugin, actions, citadels, health, materials, definitions, PaperMessages.defaults(), save = save)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        try {
            val operation = args.firstOrNull()?.lowercase(Locale.ROOT)
            if (operation == null || operation == "help") {
                sender.message("commands.help.info")
                if (sender.hasPermission(ACID)) sender.message("commands.help.acid")
                if (sender.hasPermission(DISMANTLE)) sender.message("commands.help.dismantle")
                if (sender.hasPermission(ADMIN)) {
                    sender.message("commands.help.give")
                    sender.message("commands.help.admin")
                    sender.message("commands.help.types", "types" to typeNames().joinToString())
                }
                return true
            }
            if (operation !in OPERATIONS) {
                sender.message("commands.unknown-command")
                return true
            }
            val permission = when (operation) {
                "info" -> "fornaxcitadel.info"
                "dismantle" -> DISMANTLE
                "acid" -> if (args.getOrNull(1)?.equals("info", ignoreCase = true) == true) "fornaxcitadel.info" else ACID
                else -> ADMIN
            }
            if (!sender.hasPermission(permission)) {
                sender.message("commands.no-permission")
                return true
            }
            when (operation) {
                "acid" -> acid(sender, args)
                "give" -> give(sender, args)
                "save" -> {
                    if (args.size != 1) return usage(sender, "save")
                    save()
                    sender.message("commands.saved")
                }
                "place", "remove", "dismantle", "info" -> target(sender, operation, args)
            }
        } catch (_: CitadelResolutionUnavailableException) {
            sender.message("commands.chunk-unavailable")
        } catch (exception: Exception) {
            plugin.logger.log(Level.SEVERE, "Citadel command failed", exception)
            sender.message("commands.error")
        }
        return true
    }

    private fun acid(sender: CommandSender, args: Array<out String>) {
        val information = args.size == 2 && args[1].equals("info", ignoreCase = true)
        if (args.size != 1 && !information) { usage(sender, "acid"); return }
        val player = sender as? Player ?: run { sender.message("commands.player-only"); return }
        val service = acid ?: run { sender.message("commands.acid.disabled"); return }
        val block = service.findTarget(player)
        val result = when {
            information -> service.inspect(player, block)
            block == null -> AcidResult.Failed(if (service.activationEnabled) AcidFailure.NOT_FOUND else AcidFailure.DISABLED)
            else -> {
                val request = AcidActivationRequest(acidPlayerContext(player), block.citadelPosition())
                actions.execute(request)
                request.result
            }
        }
        when (result) {
            is AcidResult.Failed -> sender.message("commands.acid.${result.reason.message}")
            is AcidResult.Activated -> sender.message("commands.acid.success", "count" to result.targets.toString())
            is AcidResult.Information -> {
                val owner = result.owner?.let { plugin.server.getPlayer(it)?.name ?: plugin.server.getOfflinePlayer(it).name ?: it.toString() }
                    ?: messages.plainText("commands.acid.unknown-owner")
                sender.message("commands.acid.info.type", "type" to result.type.id, "material" to result.type.material.name)
                sender.message("commands.acid.info.owner", "owner" to owner)
                sender.message("commands.acid.info.tier", "tier" to (result.tier?.toString() ?: messages.plainText("commands.acid.unknown-tier")))
                sender.message("commands.acid.info.maximum", "count" to result.type.maxTargets.toString())
                sender.message("commands.acid.info.multiplier", "multiplier" to result.type.maturationMultiplier.toString())
                sender.message("commands.acid.info.status", "status" to messages.plainText(
                    if (result.mature) "commands.acid.mature" else "commands.acid.maturing"))
                sender.message("commands.acid.info.available", "count" to result.availableTargets.toString())
                sender.message("commands.acid.info.selected", "count" to result.selectedTargets.toString())
            }
        }
    }

    private fun give(sender: CommandSender, args: Array<out String>) {
        val type = args.getOrNull(1)?.let(::findType)
        if (type == null || args.size !in 2..4) {
            usage(sender, "give", "types" to typeNames().joinToString("|"))
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
        val itemKey = type.itemKey
        if (itemKey == null) {
            sender.message("commands.give.missing-item", "type" to typeName(type.key))
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
        if (!citadels.isChunkLoaded(position.world, position.x shr 4, position.z shr 4)) {
            sender.message("commands.chunk-unavailable")
            return
        }
        val context = ActionContext(player.uniqueId, PlayerSource(player, COMMAND_SOURCE_KEY))
        when (operation) {
            "place" -> {
                val type = findType(args[1])
                if (type == null) {
                    sender.message("commands.unknown-type", "type" to args[1])
                    return
                }
                val result = actions.execute(PlaceAction(context, position, type.key))
                sender.message(if (result == ActionResult.SUCCESS) "commands.place.success" else "commands.place.denied")
            }
            "remove" -> {
                val result = actions.execute(RemoveAction(context, position))
                sender.message(if (result == ActionResult.SUCCESS) "commands.remove.success" else "commands.remove.denied")
            }
            "dismantle" -> {
                val result = actions.execute(DismantleAction(context, position))
                sender.message(if (result == ActionResult.SUCCESS) "commands.dismantle.success" else "commands.dismantle.denied")
            }
            "info" -> {
                val citadel = citadels.findProtecting(position)
                if (citadel == null) {
                    sender.message("commands.info.not-reinforced")
                    return
                }
                val kind = citadel.getComponent(CitadelKind::class.java)?.definitionKey
                val type = when {
                    kind == null -> messages.plainText("commands.info.unknown-type")
                    definitions.find(kind) == null -> kind.asString()
                    else -> typeName(kind)
                }
                sender.message("commands.info.type", "type" to type)
                val currentHealth = health.currentHealth(citadel)
                if (currentHealth != null) {
                    val maximum = health.maxHealth(citadel)
                    val displayedHealth = maximum?.let { currentHealth.coerceAtMost(it) } ?: currentHealth
                    sender.message(if (maximum == null) "commands.info.health-without-maximum" else "commands.info.health",
                        "health" to String.format(Locale.ROOT, "%.1f", displayedHealth),
                        "maximum" to (maximum?.let { String.format(Locale.ROOT, "%.1f", it) } ?: ""))
                }
                if (kind != null && health.isInvulnerable(kind)) sender.message("commands.info.invulnerable")
            }
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val candidates = when {
            args.size == 1 -> {
                val operations = if (sender.hasPermission(ADMIN)) OPERATIONS else listOf("info", "dismantle", "acid", "help")
                operations.filter { (it != "dismantle" || sender.hasPermission(DISMANTLE)) &&
                    (it != "acid" || sender.hasPermission(ACID) || sender.hasPermission("fornaxcitadel.info")) }
            }
            args.size == 2 && args[0].equals("acid", ignoreCase = true) && sender.hasPermission("fornaxcitadel.info") -> listOf("info")
            !sender.hasPermission(ADMIN) -> emptyList()
            args.size == 2 && args[0].lowercase(Locale.ROOT) in listOf("give", "place") -> typeNames()
            args.size == 3 && args[0].equals("give", ignoreCase = true) -> plugin.server.onlinePlayers.map { it.name }
            args.size == 4 && args[0].equals("give", ignoreCase = true) -> listOf("1", "16", "64")
            else -> emptyList()
        }
        return candidates.filter { it.startsWith(args.lastOrNull().orEmpty(), ignoreCase = true) }
    }

    private fun typeNames() = definitions.keys.map(::typeName)

    private fun typeName(key: Key) = if (key.namespace == "fornaxcitadel") key.key else key.asString()

    private fun findType(argument: String): CitadelDefinition? {
        val name = argument.lowercase(Locale.ROOT)
        val namespace = name.substringBefore(':', "fornaxcitadel")
        val value = name.substringAfter(':')
        if (namespace.isEmpty() || value.isEmpty() || !Key.parseableNamespace(namespace) || !Key.parseableValue(value)) return null
        return definitions.find(Key(namespace, value))
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
        const val DISMANTLE = "fornaxcitadel.dismantle"
        const val ACID = "fornaxcitadel.acid"
        val COMMAND_SOURCE_KEY = Key("fornaxcitadel", "paper_command")
        val OPERATIONS = listOf("give", "place", "remove", "dismantle", "acid", "info", "save", "help")
    }
}
