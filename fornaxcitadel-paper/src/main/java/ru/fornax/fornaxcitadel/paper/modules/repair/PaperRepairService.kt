package ru.fornax.fornaxcitadel.paper.modules.repair

import org.bukkit.GameMode
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.claim.ClaimAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.hasMaterial

/** Repairs authorized reinforcement, claiming unowned targets through a child action first. */
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

    /** Runs inside the action processor; a claim must finish before the repair commits. */
    fun repair(action: RepairAction): ActionResult {
        if (!enabled) return ActionResult.DENIED
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionResult.DENIED
        val citadel = checkNotNull(module.citadels.findProtecting(action.position)) {
            "Validated repair target is unavailable"
        }
        val owner = citadel.getComponent(CitadelOwner::class.java)?.playerId
        val kind = citadel.getComponent(CitadelKind::class.java)?.definitionKey
        val component = citadel.getComponent(Health::class.java)
        val health = module.health.currentHealth(citadel) ?: return ActionResult.DENIED
        if (!health.isFinite() || health < 0.0) return ActionResult.DENIED
        val maximum = module.health.maxHealth(citadel) ?: return ActionResult.DENIED
        if (health >= maximum) return ActionResult.DENIED
        val itemKey = citadelTypes.itemKey(citadel) ?: return ActionResult.DENIED
        val held = player.inventory.itemInMainHand
        val originalItem = held.clone()
        if (!held.hasMaterial() || materials.getTypeOrNull(held)?.getKey() != itemKey) return ActionResult.DENIED

        // Material adapters may run plugin code. The approved entity, owner and item must still be current.
        val currentCitadel = module.citadels.findProtecting(action.position)
        if (!player.isOnline || player.gameMode == GameMode.SPECTATOR || player.world.uid != action.citadelW ||
            !citadel.isCurrent || currentCitadel?.runtimeId != citadel.runtimeId || currentCitadel.position != citadel.position ||
            player.inventory.itemInMainHand != originalItem || held != originalItem ||
            citadel.getComponent(CitadelOwner::class.java)?.playerId != owner ||
            citadel.getComponent(CitadelKind::class.java)?.definitionKey != kind ||
            citadel.getComponent(Health::class.java) !== component ||
            module.health.currentHealth(citadel) != health || module.health.maxHealth(citadel) != maximum) {
            return ActionResult.DENIED
        }
        if (owner == null && module.actions.executeChild(ClaimAction(action.context, action.position)) != ActionResult.SUCCESS) {
            return ActionResult.DENIED
        }
        check(citadel.editComponent(Health::class.java) { it.health = maximum }) {
            "Validated repair target changed during commit"
        }
        if (player.gameMode != GameMode.CREATIVE) {
            held.amount--
            player.inventory.setItemInMainHand(if (held.amount == 0) null else held)
        }
        return ActionResult.SUCCESS
    }
}
