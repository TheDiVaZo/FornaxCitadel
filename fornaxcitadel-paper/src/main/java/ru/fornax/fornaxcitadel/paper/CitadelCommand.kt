package ru.fornax.fornaxcitadel.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.FluidCollisionMode
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.supportsCitadelData
import java.util.Locale
import java.util.logging.Level

class CitadelCommand(
    private val plugin: Plugin,
    private val manager: SimpleComponentManager,
    private val module: CitadelModule,
    private val materials: MaterialRegistry,
    private val citadelTypes: PaperCitadelTypes,
    private val save: () -> Unit
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        try {
            val operation = args.firstOrNull()?.lowercase(Locale.ROOT)
            if (operation == null || operation == "help") {
                sender.message("/citadel info — укрепление блока под прицелом")
                if (sender.hasPermission(ADMIN)) {
                    sender.message("/citadel give <тип> [игрок] [1–64]")
                    sender.message("/citadel place <тип>, /citadel remove, /citadel save")
                    sender.message("Типы: ${citadelTypes.names.joinToString()}")
                }
                return true
            }
            if (operation !in OPERATIONS) {
                sender.message("Неизвестная команда. /citadel help", error = true)
                return true
            }
            val permission = if (operation == "info") "fornaxcitadel.info" else ADMIN
            if (!sender.hasPermission(permission)) {
                sender.message("Недостаточно прав.", error = true)
                return true
            }
            when (operation) {
                "give" -> give(sender, args)
                "save" -> {
                    if (args.size != 1) return usage(sender, "/citadel save")
                    save()
                    sender.message("Данные укреплений записаны в загруженные чанки.")
                }
                "place", "remove", "info" -> target(sender, operation, args)
            }
        } catch (exception: Exception) {
            plugin.logger.log(Level.SEVERE, "Citadel command failed", exception)
            sender.message("Не удалось выполнить команду. Подробности в журнале сервера.", error = true)
        }
        return true
    }

    private fun give(sender: CommandSender, args: Array<out String>) {
        val type = args.getOrNull(1)?.let(citadelTypes::find)
        if (type == null || args.size !in 2..4) {
            usage(sender, "/citadel give <${citadelTypes.names.joinToString("|")}> [игрок] [1–64]")
            return
        }
        val recipient = args.getOrNull(2)?.let(plugin.server::getPlayerExact) ?: if (args.size == 2) sender as? Player else null
        if (recipient == null) {
            sender.message("Укажи имя игрока, находящегося на сервере.", error = true)
            return
        }
        val amount = if (args.size == 4) args[3].toIntOrNull() else 1
        if (amount == null || amount !in 1..64) {
            sender.message("Количество должно быть от 1 до 64.", error = true)
            return
        }
        val itemKey = citadelTypes.itemKey(type)
        if (itemKey == null) {
            sender.message("Для типа ${citadelTypes.name(type) ?: type.asString()} не задан предмет.", error = true)
            return
        }
        val material = materials.getType(itemKey)
        if (material == null) {
            sender.message("Предмет ${itemKey.asString()} не зарегистрирован.", error = true)
            return
        }
        val remaining = recipient.inventory.addItem(material.createItemStack(amount)).values.sumOf { it.amount }
        val given = amount - remaining
        sender.message("Выдано ${recipient.name}: $given из $amount.", error = given == 0)
        if (remaining > 0) sender.message("Для остальных предметов нет места в инвентаре.", error = true)
    }

    private fun target(sender: CommandSender, operation: String, args: Array<out String>) {
        val player = sender as? Player ?: run {
            sender.message("Эта команда выполняется игроком, смотрящим на блок.", error = true)
            return
        }
        if (args.size != if (operation == "place") 2 else 1) {
            usage(sender, if (operation == "place") "/citadel place <тип>" else "/citadel $operation")
            return
        }
        val block = player.getTargetBlockExact(6, FluidCollisionMode.NEVER)
        if (block == null || !block.supportsCitadelData()) {
            sender.message("Посмотри на поддерживаемый блок в пределах 6 блоков.", error = true)
            return
        }
        val position = CitadelPosition(block.world.uid, block.x, block.y, block.z)
        if (!manager.isChunkLoaded(position.world, position.x shr 4, position.z shr 4)) {
            sender.message("Данные этого чанка недоступны.", error = true)
            return
        }
        val context = ActionContext(player.uniqueId, COMMAND_SOURCE)
        when (operation) {
            "place" -> {
                val type = citadelTypes.find(args[1])
                if (type == null) {
                    sender.message("Неизвестный тип: ${args[1]}", error = true)
                    return
                }
                val result = module.actions.execute(PlaceAction(context, position, type))
                sender.message(if (result == ActionResult.SUCCESS) "Укрепление создано." else "Установка отклонена.", result != ActionResult.SUCCESS)
            }
            "remove" -> {
                val result = module.actions.execute(RemoveAction(context, position))
                sender.message(if (result == ActionResult.SUCCESS) "Укрепление снято. Блок сохранён." else "Удаление отклонено.", result != ActionResult.SUCCESS)
            }
            "info" -> {
                val citadel = manager.findCitadel(position.world, position.x, position.y, position.z)
                if (citadel == null) {
                    sender.message("Этот блок не укреплён.")
                    return
                }
                val kind = citadel.getComponent(CitadelKind::class.java)?.definitionKey
                sender.message("Тип: ${kind?.let(citadelTypes::name) ?: kind?.asString() ?: "неизвестен"}")
                val health = module.health.getCurrentHealth(position.world, position.x, position.y, position.z)
                val maximum = module.health.getCurrentMaxHealth(position.world, position.x, position.y, position.z)
                if (health != null && maximum != null) sender.message(String.format(Locale.ROOT, "Здоровье: %.1f / %.1f", health, maximum))
                if (kind != null && citadelTypes.isUnbreakable(kind)) sender.message("Неуязвимое укрепление.")
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

    private fun usage(sender: CommandSender, text: String): Boolean {
        sender.message(text, error = true)
        return true
    }

    private fun CommandSender.message(text: String, error: Boolean = false) {
        sendMessage(Component.text(text, if (error) NamedTextColor.RED else NamedTextColor.GREEN))
    }

    private companion object {
        const val ADMIN = "fornaxcitadel.admin"
        val COMMAND_SOURCE = Key("fornaxcitadel", "paper_command")
        val OPERATIONS = listOf("give", "place", "remove", "info", "save", "help")
    }
}
