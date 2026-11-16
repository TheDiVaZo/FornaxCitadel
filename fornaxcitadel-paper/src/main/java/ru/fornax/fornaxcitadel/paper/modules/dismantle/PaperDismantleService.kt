package ru.fornax.fornaxcitadel.paper.modules.dismantle

import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.paper.action.ActionRegistration
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import java.util.logging.Level
import kotlin.random.Random

/** Removes an authorized reinforcement and optionally refunds its material, preserving the block. */
class PaperDismantleService @JvmOverloads constructor(
    private val plugin: Plugin,
    private val module: CitadelModule,
    private val citadelTypes: PaperCitadelTypes,
    materials: MaterialRegistry,
    settings: DismantleSettings = DismantleSettings(),
    random: () -> Double = { Random.nextDouble() }
) : LifecycleService {
    private val refunds = ReinforcementRefunds(materials, settings, random)
    private val registration = ActionRegistration(
        module.actions, DismantleAction::class.java, DismantleAction.KEY,
        listOf(DismantleActionValidator(module.citadels), OwnerDismantleValidator(module.citadels)), ::dismantle
    )

    override fun enable() = registration.enable()
    override fun disable() = registration.disable()

    /**
     * Executes an action whose source and target have already passed core and platform validation.
     * Direct callers must uphold those prerequisites; external requests must enter through module.actions.
     */
    fun dismantle(action: DismantleAction): ActionResult {
        if (!registration.isEnabled) return ActionResult.DENIED
        val citadel = checkNotNull(module.citadels.findProtecting(action.position)) {
            "Validated dismantle target is unavailable"
        }
        val owner = citadel.getComponent(CitadelOwner::class.java)?.playerId ?: return ActionResult.DENIED
        val world = checkNotNull(plugin.server.getWorld(action.citadelW)) {
            "Validated dismantle world is unavailable"
        }
        val block = world.getBlockAt(action.citadelX, action.citadelY, action.citadelZ)
        val health = citadel.getComponent(Health::class.java)
        val maximum = module.health.maxHealth(citadel)
        val refundChance = refunds.chance(health == null || maximum != null && health.health >= maximum)
        val itemKey = citadelTypes.itemKey(citadel)
        val refund = try {
            refunds.create(itemKey, refundChance)
        } catch (failure: Exception) {
            plugin.logger.log(Level.WARNING, "Could not create dismantling refund for $itemKey", failure)
            return ActionResult.DENIED
        }
        if (!citadel.isCurrent || citadel.getComponent(CitadelOwner::class.java)?.playerId != owner) return ActionResult.DENIED
        val dropLocation = block.location.add(0.5, 0.5, 0.5)
        val result = module.remove.remove(citadel)
        if (result == ActionResult.SUCCESS && refund != null &&
            refunds.shouldDrop(refundChance)) {
            world.dropItemNaturally(dropLocation, refund)
        }
        return result
    }
}
