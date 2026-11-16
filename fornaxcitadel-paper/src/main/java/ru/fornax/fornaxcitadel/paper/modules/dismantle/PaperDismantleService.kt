package ru.fornax.fornaxcitadel.paper.modules.dismantle

import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.hasMaterial
import java.util.logging.Level
import kotlin.random.Random

/** Removes an authorized reinforcement and optionally refunds its material, preserving the block. */
class PaperDismantleService @JvmOverloads constructor(
    private val plugin: Plugin,
    private val module: CitadelModule,
    private val citadelTypes: PaperCitadelTypes,
    private val materials: MaterialRegistry,
    private val settings: DismantleSettings = DismantleSettings(),
    private val random: () -> Double = { Random.nextDouble() }
) : LifecycleService {
    private val validator = OwnerDismantleValidator(module.citadels)
    private val processor = object : ActionProcessor<DismantleAction>() {
        override val actionClass = DismantleAction::class.java
        override val actionKey = DismantleAction.KEY
        override fun execute(action: DismantleAction) = dismantle(action)
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

    /**
     * Executes an action whose source and target have already passed core and platform validation.
     * Direct callers must uphold those prerequisites; external requests must enter through module.actions.
     */
    fun dismantle(action: DismantleAction): ActionResult {
        if (!enabled) return ActionResult.DENIED
        val world = checkNotNull(plugin.server.getWorld(action.citadelW)) {
            "Validated dismantle world is unavailable"
        }
        val block = world.getBlockAt(action.citadelX, action.citadelY, action.citadelZ)
        val citadel = checkNotNull(module.citadels.findProtecting(action.position)) {
            "Validated dismantle target is unavailable"
        }
        val health = citadel.getComponent(Health::class.java)
        val maximum = module.health.maxHealth(citadel)
        val refundChance = if (health == null || maximum != null && health.health >= maximum) 1.0 else settings.refundChance
        val itemKey = citadelTypes.itemKey(citadel)
        val refund = if (refundChance > 0.0 && itemKey != null) {
            val material = materials.getType(itemKey) ?: return ActionResult.DENIED
            try {
                material.createItemStack(1).also {
                    require(it.hasMaterial() && it.amount == 1) { "Reinforcement material $itemKey must create one item" }
                }
            } catch (failure: Exception) {
                plugin.logger.log(Level.WARNING, "Could not create dismantling refund for $itemKey", failure)
                return ActionResult.DENIED
            }
        } else null
        if (!citadel.isCurrent) return ActionResult.DENIED
        val dropLocation = block.location.add(0.5, 0.5, 0.5)
        val result = module.remove.remove(citadel)
        if (result == ActionResult.SUCCESS && refund != null &&
            (refundChance == 1.0 || random() < refundChance)) {
            world.dropItemNaturally(dropLocation, refund)
        }
        return result
    }
}
