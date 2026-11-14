package ru.fornax.fornaxcitadel.paper.modules.dismantle

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.ReinforcementItem
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.hasMaterial
import java.util.logging.Level
import kotlin.random.Random

/** Breaks an authorized block before completing removal and its optional material refund. */
class PaperDismantleService @JvmOverloads constructor(
    private val plugin: Plugin,
    private val module: CitadelModule,
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
    private var expectedBreak: ExpectedBreak? = null

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
        expectedBreak = null
    }

    /**
     * Consumes only the expected event. The breaking listener calls this before touching cancellation;
     * all other Bukkit listeners still see the event and can reject the physical break.
     */
    fun consumeBreakEvent(player: Player, block: Block): Boolean {
        val expected = expectedBreak ?: return false
        if (expected.consumed || expected.player !== player || !expected.citadel.isCurrent ||
            expected.position.world != block.world.uid || expected.position.x != block.x ||
            expected.position.y != block.y || expected.position.z != block.z) return false
        expected.consumed = true
        return true
    }

    /**
     * Executes an action whose source and target have already passed core and platform validation.
     * Direct callers must uphold those prerequisites; external requests must enter through module.actions.
     */
    fun dismantle(action: DismantleAction): ActionResult {
        if (!enabled || expectedBreak != null) return ActionResult.DENIED
        val player = (action.context.source as PlayerSource).player
        val world = checkNotNull(plugin.server.getWorld(action.citadelW)) {
            "Validated dismantle world is unavailable"
        }
        val block = world.getBlockAt(action.citadelX, action.citadelY, action.citadelZ)
        val citadel = checkNotNull(module.citadels.findProtecting(action.position)) {
            "Validated dismantle target is unavailable"
        }
        val health = citadel.getComponent(Health::class.java)
        val refundChance = if (health == null || health.health == health.maxHealth) 1.0 else settings.refundChance
        val itemKey = citadel.getComponent(ReinforcementItem::class.java)?.materialKey
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
        expectedBreak = ExpectedBreak(player, action.position, citadel)
        val broken = try {
            player.breakBlock(block)
        } finally {
            expectedBreak = null
        }
        if (!broken) return ActionResult.DENIED
        val result = module.remove.remove(citadel)
        if (result == ActionResult.SUCCESS && refund != null &&
            (refundChance == 1.0 || random() < refundChance)) {
            world.dropItemNaturally(dropLocation, refund)
        }
        return result
    }

    private class ExpectedBreak(
        val player: Player,
        val position: CitadelPosition,
        val citadel: CitadelHandle,
        var consumed: Boolean = false
    )
}
