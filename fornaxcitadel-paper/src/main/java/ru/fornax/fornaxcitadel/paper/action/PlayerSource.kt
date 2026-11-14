package ru.fornax.fornaxcitadel.paper.action

import org.bukkit.entity.Player
import ru.fornax.fornaxcitadel.core.systems.action.ActionSource
import ru.fornax.fornaxcitadel.core.util.Key

/**
 * Holds the player that initiated an action. Read its live state on the owning game thread,
 * including online status and permissions when a queued action executes. The reference is
 * never replaced by another player with the same UUID. The key is only attribution.
 */
data class PlayerSource @JvmOverloads constructor(
    val player: Player,
    override val key: Key = KEY
) : ActionSource {
    companion object {
        @JvmField val KEY = Key("paper", "player")
    }
}
