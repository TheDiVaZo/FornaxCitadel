package ru.fornax.fornaxcitadel.paper.modules.explosion

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.Entity
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.ReadableHandle
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveService
import ru.fornax.fornaxcitadel.paper.modules.supportsCitadelData
import java.util.UUID
import java.util.logging.Level
import kotlin.math.sqrt

/** Applies validated damage and lets the explosion destroy blocks whose reinforcement was removed. */
class PaperExplosionService @JvmOverloads constructor(
    private val plugin: Plugin,
    private val actions: ActionManager,
    private val citadels: CitadelFacade,
    private val remove: RemoveService,
    powers: ExplosionPowerSettings = ExplosionPowerSettings()
) : LifecycleService {
    private val powers = powers.copy(blockPowers = powers.blockPowers.toMap())
    private val entityPowers = mutableMapOf<UUID, PrimedPower>()
    private val listener = PaperExplosionListener(this)
    private val sourceValidator = ExplosionSourceValidator()
    private var enabled = false

    override fun enable() {
        if (enabled) return
        try {
            actions.registerValidator(sourceValidator)
            plugin.server.pluginManager.registerEvents(listener, plugin)
            enabled = true
        } catch (failure: Throwable) {
            HandlerList.unregisterAll(listener)
            actions.unregisterValidator(sourceValidator)
            throw failure
        }
    }

    override fun disable() {
        if (!enabled) return
        HandlerList.unregisterAll(listener)
        actions.unregisterValidator(sourceValidator)
        enabled = false
        val primed = entityPowers.values.toList()
        entityPowers.clear()
        primed.forEach { it.cleanupTask?.cancel() }
    }

    fun recordPower(entityId: UUID, power: Float) {
        require(power.isFinite() && power >= 0.0f) { "Explosion power must be finite and non-negative" }
        if (!enabled) return
        forgetPower(entityId)
        val primed = PrimedPower(power)
        entityPowers[entityId] = primed
        // Prime and explosion run synchronously, but explosions without block interaction emit no event.
        try {
            primed.cleanupTask = plugin.server.scheduler.runTask(plugin, Runnable {
                entityPowers.remove(entityId, primed)
                primed.cleanupTask = null
            })
        } catch (failure: Throwable) {
            entityPowers.remove(entityId, primed)
            throw failure
        }
    }

    fun forgetPower(entityId: UUID) {
        entityPowers.remove(entityId)?.cleanupTask?.cancel()
    }

    fun getEntityExplosionPower(entityId: UUID): Float = entityPowers[entityId]?.power ?: powers.defaultEntityPower

    fun damageFromEntity(entity: Entity, center: Location, blocks: MutableList<Block>) {
        if (!enabled) return
        damageBlocks(blocks) {
            ExplosionSource.fromEntity(entity, center, getEntityExplosionPower(entity.uniqueId))
        }
    }

    fun damageFromBlock(source: BlockState, blocks: MutableList<Block>) {
        if (!enabled) return
        damageBlocks(blocks) {
            ExplosionSource.fromBlock(source, powers.getBlockPower(source.type))
        }
    }

    private fun damageBlocks(blocks: MutableList<Block>, sourceFactory: () -> ExplosionSource) {
        val targets = linkedMapOf<CitadelPosition, ExplosionTarget>()
        val iterator = blocks.iterator()
        while (iterator.hasNext()) {
            val block = iterator.next()
            if (!block.supportsCitadelData()) continue
            val world = block.world.uid
            if (!citadels.isChunkLoaded(world, block.x shr 4, block.z shr 4)) {
                iterator.remove()
                continue
            }
            val originalPosition = CitadelPosition(world, block.x, block.y, block.z)
            try {
                val citadel = citadels.findProtecting(originalPosition) ?: continue
                iterator.remove()
                targets.getOrPut(citadel.position) { ExplosionTarget(citadel, originalPosition) }.blocks.add(block)
            } catch (_: CitadelResolutionUnavailableException) {
                iterator.remove()
            } catch (exception: Exception) {
                iterator.remove()
                plugin.logger.log(Level.SEVERE, "Could not resolve explosion protection at $originalPosition", exception)
            }
        }
        if (targets.isEmpty()) return

        // Strip every protected block before invoking validators or any other plugin callbacks.
        val source = try {
            sourceFactory()
        } catch (exception: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not resolve a citadel explosion source", exception)
            return
        }
        val context = ActionContext(null, source)
        val destroyedBlocks = mutableListOf<Block>()
        for (target in targets.values) {
            val citadel = target.citadel
            try {
                if (!citadel.isCurrent) continue
                val position = citadel.position
                val targetPosition = ExplosionPosition(position.world,
                    position.x.toDouble(), position.y.toDouble(), position.z.toDouble())
                val damage = calculateDamage(source.power, source.position, targetPosition)
                val result = actions.execute(DamageAction(context, target.originalPosition, damage))
                if (result == ActionResult.SUCCESS && !citadel.isCurrent) destroyedBlocks.addAll(target.blocks)
            } catch (exception: Exception) {
                plugin.logger.log(Level.SEVERE, "Could not apply explosion damage at ${citadel.position}", exception)
            }
        }
        // Keep all protected blocks excluded during callbacks; vanilla handles destruction afterward.
        blocks.addAll(destroyedBlocks)
    }

    /** Reconciles reinforcement data for sources that Paper removed before firing BlockExplodeEvent. */
    fun reconcileRemovedSource(source: BlockState) {
        if (!enabled) return
        try {
            if (source.type == Material.RESPAWN_ANCHOR) {
                removeAt(source.location)
                return
            }
            val bed = source.blockData as? Bed ?: return
            val location = source.location
            removeAt(location)
            val face = if (bed.part == Bed.Part.HEAD) bed.facing.oppositeFace else bed.facing
            removeAt(location.clone().add(face.modX.toDouble(), face.modY.toDouble(), face.modZ.toDouble()))
        } catch (exception: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not reconcile a removed explosion source", exception)
        }
    }

    private fun removeAt(location: Location) {
        val world = location.world?.uid ?: return
        val position = CitadelPosition(world, location.blockX, location.blockY, location.blockZ)
        val citadel = citadels.findExact(position) ?: return
        remove.remove(citadel)
    }

    private class ExplosionTarget(val citadel: ReadableHandle, val originalPosition: CitadelPosition) {
        val blocks = mutableListOf<Block>()
    }

    private class PrimedPower(val power: Float) {
        var cleanupTask: BukkitTask? = null
    }

    companion object {
        /** Uses block coordinates without a half-block offset, matching the original damage model. */
        @JvmStatic
        fun calculateDamage(power: Float, explosion: ExplosionPosition, target: ExplosionPosition): Double {
            require(power.isFinite() && power >= 0.0f) { "Explosion power must be finite and non-negative" }
            require(explosion.worldId == target.worldId) { "Explosion source and target must be in the same world" }
            val dx = explosion.x - target.x
            val dy = explosion.y - target.y
            val dz = explosion.z - target.z
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            require(distance.isFinite()) { "Explosion distance must be finite" }
            val radius = power * 2.0
            if (distance >= radius) return 0.0
            val distanceFactor = 1.0 - distance / radius
            return power * 10.0 * distanceFactor * distanceFactor
        }
    }
}
