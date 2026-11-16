package ru.fornax.fornaxcitadel.paper.modules.repair

import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.paper.action.ActionRegistration
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.consumeOneFromMainHand
import ru.fornax.fornaxcitadel.paper.material.hasMaterial

/** Restores an authorized, owned reinforcement to its current maximum health. */
class PaperRepairService(
    private val module: CitadelModule,
    private val citadelTypes: PaperCitadelTypes,
    private val materials: MaterialRegistry
) : LifecycleService {
    private val registration = ActionRegistration(
        module.actions, RepairAction::class.java, RepairAction.KEY,
        listOf(RepairActionValidator(module.citadels), OwnerRepairValidator(module.citadels)), ::repair
    )

    override fun enable() = registration.enable()
    override fun disable() = registration.disable()

    /** Executes a validated action; external requests must enter through module.actions. */
    fun repair(action: RepairAction): ActionResult {
        if (!registration.isEnabled) return ActionResult.DENIED
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionResult.DENIED
        val citadel = checkNotNull(module.citadels.findProtecting(action.position)) {
            "Validated repair target is unavailable"
        }
        if (citadel.getComponent(CitadelOwner::class.java) == null) return ActionResult.DENIED
        val health = citadel.getComponent(Health::class.java)?.health ?: return ActionResult.DENIED
        if (!health.isFinite() || health < 0.0) return ActionResult.DENIED
        val maximum = module.health.maxHealth(citadel) ?: return ActionResult.DENIED
        if (health >= maximum) return ActionResult.DENIED
        val itemKey = citadelTypes.itemKey(citadel) ?: return ActionResult.DENIED
        val held = player.inventory.itemInMainHand
        if (!held.hasMaterial() || materials.getTypeOrNull(held)?.getKey() != itemKey) return ActionResult.DENIED

        check(citadel.editComponent(Health::class.java) { it.health = maximum }) {
            "Validated repair target changed during commit"
        }
        player.consumeOneFromMainHand(held)
        return ActionResult.SUCCESS
    }
}
