package ru.fornax.fornaxcitadel.paper.modules.explosion

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionDamageAction.ExplosionSource
import ru.fornax.fornaxcitadel.paper.modules.supportsCitadelData
import java.util.UUID
import java.util.logging.Level

/** Converts vanilla destruction into validated damage while keeping reinforced blocks intact. */
class PaperExplosionService @JvmOverloads constructor(
    powers: ExplosionPowerSettings = ExplosionPowerSettings()
) {
    private val powers = powers.copy(blockPowers = powers.blockPowers.toMap())
    private val entityPowers = mutableMapOf<UUID, PrimedPower>()
    private lateinit var plugin: Plugin
    private lateinit var module: CitadelModule
    private lateinit var manager: SimpleComponentManager

    fun init(plugin: Plugin, module: CitadelModule) {
        check(!::manager.isInitialized) { "Explosion service is already initialized" }
        this.plugin = plugin
        this.module = module
        manager = module.actions.componentManager
        plugin.server.pluginManager.registerEvents(PaperExplosionListener(this), plugin)
    }

    fun recordPower(entityId: UUID, power: Float) {
        require(power.isFinite() && power >= 0.0f) { "Explosion power must be finite and non-negative" }
        val primed = PrimedPower(power)
        entityPowers[entityId] = primed
        // Prime and explosion run synchronously, but explosions without block interaction emit no event.
        plugin.server.scheduler.runTask(plugin, Runnable { entityPowers.remove(entityId, primed) })
    }

    fun forgetPower(entityId: UUID) {
        entityPowers.remove(entityId)
    }

    fun getEntityExplosionPower(entityId: UUID): Float = entityPowers[entityId]?.power ?: powers.defaultEntityPower

    fun damageFromEntity(entity: Entity, center: Location, blocks: MutableList<Block>) {
        damageBlocks(blocks) {
            ExplosionSource.Entity(center.clone(), getEntityExplosionPower(entity.uniqueId), entity)
        }
    }

    fun damageFromBlock(source: BlockState, blocks: MutableList<Block>) {
        damageBlocks(blocks) {
            ExplosionSource.Block(source.location.clone(), powers.getBlockPower(source.type), source)
        }
    }

    private fun damageBlocks(blocks: MutableList<Block>, sourceFactory: () -> ExplosionSource) {
        val targets = mutableListOf<CitadelHandle>()
        val iterator = blocks.iterator()
        while (iterator.hasNext()) {
            val block = iterator.next()
            if (!block.supportsCitadelData()) continue
            val world = block.world.uid
            if (!manager.isChunkLoaded(world, block.x shr 4, block.z shr 4)) {
                iterator.remove()
                continue
            }
            val citadel = manager.findCitadel(world, block.x, block.y, block.z) ?: continue
            iterator.remove()
            targets.add(citadel)
        }
        if (targets.isEmpty()) return

        // Strip every protected block before invoking validators or any other plugin callbacks.
        val source = try {
            sourceFactory()
        } catch (exception: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not resolve a citadel explosion source", exception)
            return
        }
        for (citadel in targets) {
            try {
                if (!citadel.isCurrent) continue
                val position = citadel.position
                val target = Location(source.centerLocation.world,
                    position.x.toDouble(), position.y.toDouble(), position.z.toDouble())
                val damage = calculateDamage(source.power, source.centerLocation, target)
                module.actions.execute(ExplosionDamageAction(position, source, damage))
            } catch (exception: Exception) {
                plugin.logger.log(Level.SEVERE, "Could not apply explosion damage at ${citadel.position}", exception)
            }
        }
    }

    /** Reconciles reinforcement data for sources that Paper removed before firing BlockExplodeEvent. */
    fun reconcileRemovedSource(source: BlockState) {
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
        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ
        if (!CitadelPosition.isSupported(x, y, z) || !manager.isChunkLoaded(world, x shr 4, z shr 4)) return
        val citadel = manager.findCitadel(world, x, y, z) ?: return
        module.remove.remove(citadel, RemovalCause.WORLD_EDIT)
    }

    private class PrimedPower(val power: Float)

    companion object {
        /** Uses block coordinates without a half-block offset, matching the original damage model. */
        @JvmStatic
        fun calculateDamage(power: Float, explosion: Location, target: Location): Double {
            require(power.isFinite() && power >= 0.0f) { "Explosion power must be finite and non-negative" }
            val distance = explosion.distance(target)
            require(distance.isFinite()) { "Explosion distance must be finite" }
            val radius = power * 2.0
            if (distance >= radius) return 0.0
            val distanceFactor = 1.0 - distance / radius
            return power * 10.0 * distanceFactor * distanceFactor
        }
    }
}
