package ru.fornax.fornaxcitadel.paper.modules.explosion

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockState
import org.bukkit.entity.Entity as BukkitEntity
import org.bukkit.entity.EntityType
import ru.fornax.fornaxcitadel.core.systems.action.ActionSource
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID

/** An immutable explosion snapshot. No live entities, blocks or mutable locations are retained. */
sealed interface ExplosionSource : ActionSource {
    override val key: Key get() = KEY
    val position: ExplosionPosition
    val power: Float

    data class Entity(
        override val position: ExplosionPosition,
        override val power: Float,
        val entityId: UUID,
        val entityType: EntityType
    ) : ExplosionSource {
        init { requireValidPower(power) }
    }

    data class Block(
        override val position: ExplosionPosition,
        override val power: Float,
        val material: Material
    ) : ExplosionSource {
        init { requireValidPower(power) }
    }

    companion object {
        @JvmField val KEY = Key("paper", "explosion")

        @JvmStatic
        fun fromEntity(entity: BukkitEntity, center: Location, power: Float): Entity =
            Entity(ExplosionPosition.from(center), power, entity.uniqueId, entity.type)

        @JvmStatic
        fun fromBlock(block: BlockState, power: Float): Block =
            Block(ExplosionPosition.from(block.location), power, block.type)

        private fun requireValidPower(power: Float) {
            require(power.isFinite() && power >= 0.0f) { "Explosion power must be finite and non-negative" }
        }
    }
}
