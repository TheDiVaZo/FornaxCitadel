package ru.fornax.fornaxcitadel.paper.modules.breaking.damage

import org.bukkit.entity.Player
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle

/** Read-only damage calculation. Do not mutate or retain the live citadel or its components. */
fun interface PlayerDamageModifier : Comparable<PlayerDamageModifier> {
    fun priority(): Priority = Priority.NORMAL

    fun getDamage(player: Player, citadel: CitadelHandle, currentDamage: Double): Double

    override fun compareTo(other: PlayerDamageModifier): Int = priority().value.compareTo(other.priority().value)

    /** Higher priority runs first; LOWEST runs last. Equal priorities retain registration order. */
    enum class Priority(val value: Int) {
        LOWEST(5), LOW(4), NORMAL(3), HIGH(2), HIGHEST(1)
    }
}
