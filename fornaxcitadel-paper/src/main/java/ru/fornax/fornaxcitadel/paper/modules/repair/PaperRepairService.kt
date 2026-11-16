package ru.fornax.fornaxcitadel.paper.modules.repair

import org.bukkit.GameMode
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
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
        module.actions, RepairAction::class.java, RepairAction.KEY, OwnerRepairValidator(module.citadels), ::repair
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
        val owner = citadel.getComponent(CitadelOwner::class.java)?.playerId ?: return ActionResult.DENIED
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
        check(citadel.editComponent(Health::class.java) { it.health = maximum }) {
            "Validated repair target changed during commit"
        }
        player.consumeOneFromMainHand(held)
        return ActionResult.SUCCESS
    }
}
