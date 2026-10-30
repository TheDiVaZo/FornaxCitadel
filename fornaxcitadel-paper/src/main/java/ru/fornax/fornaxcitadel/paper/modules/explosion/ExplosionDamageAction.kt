package ru.fornax.fornaxcitadel.paper.modules.explosion

import org.bukkit.Location
import org.bukkit.block.BlockState
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction

/** Environmental damage that retains the core damage processor and all common damage validators. */
class ExplosionDamageAction(
    position: CitadelPosition,
    source: ExplosionSource,
    damage: Double
) : DamageAction(ActionContext(null, EXPLOSION_SOURCE), position, damage) {
    // Bukkit Location is mutable; validators must not change the origin of later target actions.
    val source: ExplosionSource = when (source) {
        is ExplosionSource.Block -> source.copy(centerLocation = source.centerLocation.clone())
        is ExplosionSource.Entity -> source.copy(centerLocation = source.centerLocation.clone())
    }

    init {
        require(this.source.power.isFinite() && this.source.power >= 0.0f) {
            "Explosion power must be finite and non-negative: ${this.source.power}"
        }
        val center = this.source.centerLocation
        require(center.x.isFinite() && center.y.isFinite() && center.z.isFinite()) {
            "Explosion center coordinates must be finite"
        }
        require(center.world?.uid == position.world) {
            "Explosion source and target must be in the same world"
        }
    }

    sealed interface ExplosionSource {
        val centerLocation: Location
        val power: Float

        data class Block(
            override val centerLocation: Location,
            override val power: Float,
            val blockState: BlockState
        ) : ExplosionSource

        data class Entity(
            override val centerLocation: Location,
            override val power: Float,
            val entity: org.bukkit.entity.Entity
        ) : ExplosionSource
    }

    private companion object {
        val EXPLOSION_SOURCE = Key("paper", "explosion")
    }
}
