package ru.fornax.fornaxcitadel.paper.modules.explosion

import org.bukkit.Location
import java.util.UUID

/** Immutable coordinates captured when the explosion occurs, including fractional entity positions. */
data class ExplosionPosition(val worldId: UUID, val x: Double, val y: Double, val z: Double) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Explosion coordinates must be finite" }
    }

    companion object {
        @JvmStatic
        fun from(location: Location): ExplosionPosition = ExplosionPosition(
            requireNotNull(location.world) { "Explosion location must have a world" }.uid,
            location.x, location.y, location.z
        )
    }
}
