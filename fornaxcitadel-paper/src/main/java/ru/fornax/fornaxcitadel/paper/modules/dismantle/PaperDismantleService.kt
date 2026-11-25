package ru.fornax.fornaxcitadel.paper.modules.dismantle

import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionRegistration
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ExistingCitadelValidator
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.remove.RemoveService
import ru.fornax.fornaxcitadel.paper.action.OwnerActionValidator
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import java.util.logging.Level
import kotlin.random.Random

/** Removes an authorized reinforcement and optionally refunds its material, preserving the block. */
class PaperDismantleService @JvmOverloads constructor(
    private val plugin: Plugin,
    actions: ActionManager,
    private val citadels: CitadelFacade,
    private val health: HealthService,
    private val remove: RemoveService,
    private val citadelTypes: PaperCitadelTypes,
    materials: MaterialRegistry,
    settings: DismantleSettings = DismantleSettings(),
    random: () -> Double = { Random.nextDouble() }
) : LifecycleService {
    private val refunds = ReinforcementRefunds(materials, settings, random)
    private val registration = ActionRegistration(
        actions, DismantleAction::class.java, DismantleAction.KEY,
        listOf(
            ExistingCitadelValidator(DismantleAction::class.java, citadels),
            OwnerActionValidator(DismantleAction::class.java, citadels)
        ), ::dismantle
    )

    override fun enable() {
        if (!registration.isEnabled) registration.enable()
    }
    override fun disable() = registration.disable()

    /**
     * Executes an action whose source and target have already passed core and platform validation.
     * Direct callers must uphold those prerequisites. External requests use [ActionManager.execute].
     */
    fun dismantle(action: DismantleAction): ActionResult {
        if (!registration.isEnabled) return ActionResult.DENIED
        val citadel = checkNotNull(citadels.findProtecting(action.position)) {
            "Validated dismantle target is unavailable"
        }
        if (citadel.getComponent(CitadelOwner::class.java) == null) return ActionResult.DENIED
        val world = checkNotNull(plugin.server.getWorld(action.citadelW)) {
            "Validated dismantle world is unavailable"
        }
        val block = world.getBlockAt(action.citadelX, action.citadelY, action.citadelZ)
        val healthComponent = citadel.getComponent(Health::class.java)
        val maximum = health.maxHealth(citadel)
        val refundChance = refunds.chance(healthComponent == null || maximum != null && healthComponent.health >= maximum)
        val itemKey = citadelTypes.itemKey(citadel)
        val refund = try {
            refunds.create(itemKey, refundChance)
        } catch (failure: Exception) {
            plugin.logger.log(Level.WARNING, "Could not create dismantling refund for $itemKey", failure)
            return ActionResult.DENIED
        }
        val dropLocation = block.location.add(0.5, 0.5, 0.5)
        val result = remove.remove(citadel)
        if (result == ActionResult.SUCCESS && refund != null &&
            refunds.shouldDrop(refundChance)) {
            world.dropItemNaturally(dropLocation, refund)
        }
        return result
    }
}
