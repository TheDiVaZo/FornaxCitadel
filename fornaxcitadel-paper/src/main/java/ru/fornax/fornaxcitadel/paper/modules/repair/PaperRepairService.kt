package ru.fornax.fornaxcitadel.paper.modules.repair

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.HealthFacade
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ExistingCitadelValidator
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.paper.action.OwnerActionValidator
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.consumeOneFromMainHand
import ru.fornax.fornaxcitadel.paper.material.hasMaterial

/** Restores an authorized, owned reinforcement to its current maximum health. */
class PaperRepairService(
    actions: ActionFacade,
    private val citadels: CitadelFacade,
    private val health: HealthFacade,
    private val definitions: CitadelDefinitionRegistry,
    private val materials: MaterialRegistry
) : ActionProcessor<RepairAction>(), LifecycleService {
    private var enabled = false
    override val actionClass = RepairAction::class.java
    override val actionKey = RepairAction.KEY

    init {
        actions.registerAction(this)
        actions.registerValidator(ExistingCitadelValidator(RepairAction::class.java, citadels))
        actions.registerValidator(OwnerActionValidator(RepairAction::class.java, citadels))
    }

    override fun enable() {
        enabled = true
    }

    override fun disable() {
        enabled = false
    }

    override fun execute(action: RepairAction) = repair(action)

    /** Executes a validated action. External requests use [ActionFacade.execute]. */
    fun repair(action: RepairAction): ActionResult {
        if (!enabled) return ActionResult.DENIED
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionResult.DENIED
        val citadel = checkNotNull(citadels.findProtecting(action.position)) {
            "Validated repair target is unavailable"
        }
        if (citadel.getComponent(CitadelOwner::class.java) == null) return ActionResult.DENIED
        val currentHealth = citadel.getComponent(Health::class.java)?.health ?: return ActionResult.DENIED
        if (!currentHealth.isFinite() || currentHealth < 0.0) return ActionResult.DENIED
        val maximum = health.maxHealth(citadel) ?: return ActionResult.DENIED
        if (currentHealth >= maximum) return ActionResult.DENIED
        val itemKey = definitions.itemKey(citadel) ?: return ActionResult.DENIED
        val held = player.inventory.itemInMainHand
        if (!held.hasMaterial() || materials.getTypeOrNull(held)?.getKey() != itemKey) return ActionResult.DENIED

        check(citadel.editComponent(Health::class.java) { it.health = maximum }) {
            "Validated repair target changed during commit"
        }
        player.consumeOneFromMainHand(held)
        return ActionResult.SUCCESS
    }
}
