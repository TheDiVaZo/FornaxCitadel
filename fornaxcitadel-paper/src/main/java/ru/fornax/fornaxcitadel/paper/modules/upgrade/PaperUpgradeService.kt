package ru.fornax.fornaxcitadel.paper.modules.upgrade

import org.bukkit.block.Block
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.action.OwnerActionValidator
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.consumeOneFromMainHand
import ru.fornax.fornaxcitadel.paper.material.hasMaterial
import ru.fornax.fornaxcitadel.paper.modules.dismantle.DismantleSettings
import ru.fornax.fornaxcitadel.paper.modules.dismantle.ReinforcementRefunds
import java.util.logging.Level
import kotlin.random.Random

/** Upgrades a live entity in place; neither dismantles it nor emits a separate placement action. */
class PaperUpgradeService @JvmOverloads constructor(
    private val plugin: Plugin,
    actions: ActionFacade,
    private val citadels: CitadelFacade,
    private val definitions: CitadelDefinitionRegistry,
    private val health: HealthService,
    private val materials: MaterialRegistry,
    settings: DismantleSettings = DismantleSettings(),
    random: () -> Double = { Random.nextDouble() }
) : ActionProcessor<UpgradeAction>(), LifecycleService {
    private val refunds = ReinforcementRefunds(materials, settings, random)
    private var enabled = false
    override val actionClass = UpgradeAction::class.java
    override val actionKey = UpgradeAction.KEY

    init {
        actions.registerAction(this)
        actions.registerValidator(UpgradeActionValidator(citadels, definitions))
        actions.registerValidator(OwnerActionValidator(UpgradeAction::class.java, citadels, ActionValidator.Result.Rejected))
    }

    override fun enable() {
        enabled = true
    }

    override fun disable() {
        enabled = false
    }

    override fun execute(action: UpgradeAction) = upgrade(action)

    /** Executes a validated action. External requests use [ActionFacade.execute]. */
    fun upgrade(action: UpgradeAction): ActionResult {
        if (!enabled || !plugin.server.isPrimaryThread) return ActionResult.DENIED
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionResult.DENIED
        val citadel = citadels.findProtecting(action.position) ?: return ActionResult.DENIED
        if (citadel.getComponent(CitadelOwner::class.java) == null) return ActionResult.DENIED
        val destination = definitions.upgradeDestination(citadel, action.definitionKey) ?: return ActionResult.DENIED
        val itemKey = destination.itemKey ?: return ActionResult.DENIED
        val held = player.inventory.itemInMainHand
        if (!held.hasMaterial()) return ActionResult.DENIED

        val oldMaximum = health.maxHealth(citadel)
        val block = findLoadedBlock(citadel.position) ?: return ActionResult.DENIED
        if (materials.getTypeOrNull(held)?.getKey() != itemKey) return ActionResult.DENIED
        val currentHealth = health.currentHealth(citadel)
        val refundChance = refunds.chance(
            currentHealth != null && currentHealth.isFinite() && oldMaximum != null && currentHealth >= oldMaximum)
        val refund = try {
            refunds.create(definitions.itemKey(citadel), refundChance)
        } catch (failure: Exception) {
            plugin.logger.log(Level.WARNING, "Could not create upgrade refund at ${citadel.position}", failure)
            return ActionResult.DENIED
        }
        val dropRefund = refund != null && refunds.shouldDrop(refundChance)

        destination.update(citadel)
        player.consumeOneFromMainHand(held)
        if (dropRefund) block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), checkNotNull(refund))
        return ActionResult.SUCCESS
    }

    private fun findLoadedBlock(position: CitadelPosition): Block? {
        val world = plugin.server.getWorld(position.world) ?: return null
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return null
        val block = world.getBlockAt(position.x, position.y, position.z)
        return block.takeUnless { it.isEmpty }
    }
}
