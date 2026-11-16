package ru.fornax.fornaxcitadel.paper.modules.repair

import org.bukkit.GameMode
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.hasMaterial

/** Restores authorized reinforcement to full health using one item of its original material. */
class PaperRepairService(
    private val module: CitadelModule,
    private val citadelTypes: PaperCitadelTypes,
    private val materials: MaterialRegistry
) : LifecycleService {
    private val validator = OwnerRepairValidator(module.citadels)
    private val processor = object : ActionProcessor<RepairAction>() {
        override val actionClass = RepairAction::class.java
        override val actionKey = RepairAction.KEY
        override fun execute(action: RepairAction) = repair(action)
    }
    private var enabled = false

    override fun enable() {
        if (enabled) return
        module.actions.processorCoordinator.registerProcessor(processor)
        try {
            module.actions.validatorCoordinator.registerValidator(validator)
            enabled = true
        } catch (failure: Throwable) {
            module.actions.processorCoordinator.unregisterProcessor(processor)
            throw failure
        }
    }

    override fun disable() {
        module.actions.validatorCoordinator.unregisterValidator(validator)
        module.actions.processorCoordinator.unregisterProcessor(processor)
        enabled = false
    }

    /** Executes a validated action; external requests must enter through module.actions. */
    fun repair(action: RepairAction): ActionResult {
        if (!enabled) return ActionResult.DENIED
        val player = (action.context.source as PlayerSource).player
        val citadel = checkNotNull(module.citadels.findProtecting(action.position)) {
            "Validated repair target is unavailable"
        }
        val health = module.health.currentHealth(citadel) ?: return ActionResult.DENIED
        val maximum = module.health.maxHealth(citadel) ?: return ActionResult.DENIED
        if (health >= maximum) return ActionResult.DENIED
        val itemKey = citadelTypes.itemKey(citadel) ?: return ActionResult.DENIED
        val held = player.inventory.itemInMainHand
        if (!held.hasMaterial() || materials.getTypeOrNull(held)?.getKey() != itemKey) return ActionResult.DENIED
        if (!citadel.editComponent(Health::class.java) { it.health = maximum }) return ActionResult.DENIED
        if (player.gameMode != GameMode.CREATIVE) {
            held.amount--
            player.inventory.setItemInMainHand(if (held.amount == 0) null else held)
        }
        return ActionResult.SUCCESS
    }
}
