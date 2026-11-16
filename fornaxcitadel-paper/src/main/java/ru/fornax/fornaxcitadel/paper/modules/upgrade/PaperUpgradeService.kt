package ru.fornax.fornaxcitadel.paper.modules.upgrade

import org.bukkit.GameMode
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.action.ActionRegistration
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
        listOf(UpgradeActionValidator(module.citadels, module.definitions), OwnerUpgradeValidator(module.citadels)), ::upgrade
    )

    override fun enable() = registration.enable()
    override fun disable() = registration.disable()

    /** Executes a validated action; external requests must enter through module.actions. */
    fun upgrade(action: UpgradeAction): ActionResult {
        if (!registration.isEnabled || !plugin.server.isPrimaryThread) return ActionResult.DENIED
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionResult.DENIED
        val citadel = module.citadels.findProtecting(action.position) ?: return ActionResult.DENIED
        val oldOwner = citadel.getComponent(CitadelOwner::class.java)?.playerId ?: return ActionResult.DENIED
        val oldKey = citadel.getComponent(CitadelKind::class.java)?.definitionKey
        if (oldKey == action.definitionKey) return ActionResult.DENIED
        val comparison = comparison(citadel, action.definitionKey) ?: return ActionResult.DENIED
        if (!comparison.isImprovement()) return ActionResult.DENIED
        val itemKey = citadelTypes.itemKey(action.definitionKey) ?: return ActionResult.DENIED
        val held = player.inventory.itemInMainHand
        if (!held.hasMaterial()) return ActionResult.DENIED

        val originalItem = held.clone()
        val oldHealth = citadel.getComponent(Health::class.java)?.health
        if (oldHealth != null && (!oldHealth.isFinite() || oldHealth < 0.0)) return ActionResult.DENIED
        val oldMaximum = module.health.maxHealth(citadel)
        val oldStartedAt = citadel.getComponent(Maturing::class.java)?.startedAtMillis
        val position = citadel.position
        val world = plugin.server.getWorld(position.world) ?: return ActionResult.DENIED
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return ActionResult.DENIED
        val block = world.getBlockAt(position.x, position.y, position.z)
        if (block.isEmpty) return ActionResult.DENIED
        val originalBlockData = block.blockData.asString
        if (materials.getTypeOrNull(held)?.getKey() != itemKey) return ActionResult.DENIED
        val currentHealth = module.health.currentHealth(citadel)
        val refundChance = refunds.chance(currentHealth != null && oldMaximum != null && currentHealth >= oldMaximum)
        val refund = try {
            refunds.create(oldKey?.let(citadelTypes::itemKey), refundChance)
        } catch (failure: Exception) {
            plugin.logger.log(Level.WARNING, "Could not create upgrade refund at ${citadel.position}", failure)
            return ActionResult.DENIED
        }
        val dropRefund = refund != null && refunds.shouldDrop(refundChance)
        val startedAt = clock.millis()

        // Material providers may execute plugin code. Reject stale state before changing anything ourselves.
        val currentItem = player.inventory.itemInMainHand
        if (materials.getTypeOrNull(currentItem)?.getKey() != itemKey) return ActionResult.DENIED
        val currentCitadel = try {
            module.citadels.findProtecting(action.position)
        } catch (_: CitadelResolutionUnavailableException) {
            return ActionResult.DENIED
        }
        if (!player.isOnline || player.gameMode == GameMode.SPECTATOR || player.world.uid != action.citadelW ||
            player.inventory.itemInMainHand != originalItem || currentItem != originalItem ||
            !citadel.isCurrent || currentCitadel?.runtimeId != citadel.runtimeId || currentCitadel.position != citadel.position ||
            citadel.getComponent(CitadelKind::class.java)?.definitionKey != oldKey ||
            citadel.getComponent(Health::class.java)?.health != oldHealth ||
            module.health.maxHealth(citadel) != oldMaximum ||
            citadel.getComponent(CitadelOwner::class.java)?.playerId != oldOwner ||
            citadel.getComponent(Maturing::class.java)?.startedAtMillis != oldStartedAt ||
            comparison(citadel, action.definitionKey) != comparison) return ActionResult.DENIED
        if (plugin.server.getWorld(position.world) == null ||
            !world.isChunkLoaded(position.x shr 4, position.z shr 4) || block.blockData.asString != originalBlockData) {
            return ActionResult.DENIED
        }

        val manager = module.actions.componentManager
        manager.putComponent(CitadelKind::class.java, position.world, position.x, position.y, position.z,
            CitadelKind(action.definitionKey))
        if (comparison.newHealth == null) {
            manager.removeComponent(Health::class.java, position.world, position.x, position.y, position.z)
        } else {
            manager.putComponent(Health::class.java, position.world, position.x, position.y, position.z,
                Health(comparison.newHealth))
        }
        manager.putComponent(Maturing::class.java, position.world, position.x, position.y, position.z, Maturing(startedAt))
        player.consumeOneFromMainHand(currentItem)
        if (dropRefund) world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), checkNotNull(refund))
        return ActionResult.SUCCESS
    }

    private fun comparison(citadel: CitadelHandle, destination: Key): UpgradeComparison? {
        if (module.definitions.find(destination) == null) return null
        val newLevel = citadelTypes.level(destination) ?: return null
        val oldKey = citadel.getComponent(CitadelKind::class.java)?.definitionKey
        return UpgradeComparison(
            citadelTypes.level(citadel),
            module.health.maxHealth(citadel) ?: module.health.currentHealth(citadel),
            oldKey?.let(citadelTypes::isInvulnerable) == true,
            newLevel,
            module.health.findDefinition(destination)?.maxHealth,
            citadelTypes.isInvulnerable(destination)
        )
    }
}
