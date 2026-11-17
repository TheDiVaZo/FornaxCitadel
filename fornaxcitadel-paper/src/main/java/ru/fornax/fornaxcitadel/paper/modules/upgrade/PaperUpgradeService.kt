package ru.fornax.fornaxcitadel.paper.modules.upgrade

import org.bukkit.block.Block
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.ReadableHandle
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionRegistration
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.action.OwnerActionValidator
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.consumeOneFromMainHand
import ru.fornax.fornaxcitadel.paper.material.hasMaterial
import ru.fornax.fornaxcitadel.paper.modules.dismantle.DismantleSettings
import ru.fornax.fornaxcitadel.paper.modules.dismantle.ReinforcementRefunds
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import java.time.Clock
import java.util.logging.Level
import kotlin.random.Random

/** Upgrades a live entity in place; neither dismantles it nor emits a separate placement action. */
class PaperUpgradeService @JvmOverloads constructor(
    private val plugin: Plugin,
    private val module: CitadelModule,
    private val citadelTypes: PaperCitadelTypes,
    private val materials: MaterialRegistry,
    settings: DismantleSettings = DismantleSettings(),
    random: () -> Double = { Random.nextDouble() },
    private val clock: Clock = Clock.systemUTC()
) : LifecycleService {
    private val refunds = ReinforcementRefunds(materials, settings, random)
    private val registration = ActionRegistration(
        module.actions, UpgradeAction::class.java, UpgradeAction.KEY,
        listOf(
            UpgradeActionValidator(module.citadels, module.definitions),
            OwnerActionValidator(UpgradeAction::class.java, module.citadels, ActionValidator.Result.Rejected)
        ), ::upgrade
    )

    override fun enable() {
        if (!registration.isEnabled) registration.enable()
    }
    override fun disable() = registration.disable()

    /** Executes a validated action; external requests must enter through module.actions. */
    fun upgrade(action: UpgradeAction): ActionResult {
        if (!registration.isEnabled || !plugin.server.isPrimaryThread) return ActionResult.DENIED
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionResult.DENIED
        val citadel = module.citadels.findProtecting(action.position) ?: return ActionResult.DENIED
        if (citadel.getComponent(CitadelOwner::class.java) == null) return ActionResult.DENIED
        val oldKey = citadel.getComponent(CitadelKind::class.java)?.definitionKey
        if (!canUpgradeTo(citadel, action.definitionKey)) return ActionResult.DENIED
        val newMaximum = module.health.findDefinition(action.definitionKey)?.maxHealth
        val itemKey = citadelTypes.itemKey(action.definitionKey) ?: return ActionResult.DENIED
        val held = player.inventory.itemInMainHand
        if (!held.hasMaterial()) return ActionResult.DENIED

        val oldMaximum = module.health.maxHealth(citadel)
        val block = findLoadedBlock(citadel.position) ?: return ActionResult.DENIED
        if (materials.getTypeOrNull(held)?.getKey() != itemKey) return ActionResult.DENIED
        val currentHealth = module.health.currentHealth(citadel)
        val refundChance = refunds.chance(
            currentHealth != null && currentHealth.isFinite() && oldMaximum != null && currentHealth >= oldMaximum)
        val refund = try {
            refunds.create(oldKey?.let(citadelTypes::itemKey), refundChance)
        } catch (failure: Exception) {
            plugin.logger.log(Level.WARNING, "Could not create upgrade refund at ${citadel.position}", failure)
            return ActionResult.DENIED
        }
        val dropRefund = refund != null && refunds.shouldDrop(refundChance)
        val startedAt = clock.millis()

        applyUpgrade(citadel, action.definitionKey, newMaximum, startedAt)
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

    private fun applyUpgrade(citadel: CitadelHandle, destination: Key, maximum: Double?, startedAtMillis: Long) {
        citadel.putComponent(CitadelKind::class.java, CitadelKind(destination))
        if (maximum == null) {
            citadel.removeComponent(Health::class.java)
        } else {
            citadel.putComponent(Health::class.java, Health(maximum))
        }
        citadel.putComponent(Maturing::class.java, Maturing(startedAtMillis))
    }

    private fun canUpgradeTo(citadel: ReadableHandle, destination: Key): Boolean {
        if (module.definitions.find(destination) == null) return false
        val newLevel = citadelTypes.level(destination) ?: return false
        val oldLevel = citadelTypes.level(citadel)
        return oldLevel == null || newLevel > oldLevel
    }
}
