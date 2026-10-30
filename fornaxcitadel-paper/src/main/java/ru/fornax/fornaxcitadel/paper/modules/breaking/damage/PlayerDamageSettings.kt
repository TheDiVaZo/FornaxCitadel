package ru.fornax.fornaxcitadel.paper.modules.breaking.damage

import org.bukkit.configuration.ConfigurationSection
import java.time.Duration

/** Optional built-in modifiers; an unmodified player hit always starts at one damage. */
data class PlayerDamageSettings(
    val creativeModeEnabled: Boolean = true,
    val newcomersEnabled: Boolean = false,
    val newcomerFirstJoin: Duration = Duration.ofHours(48),
    val newcomerPlayTimeTicks: Long = 864_000L
) {
    init {
        require(newcomerFirstJoin.toMillis() > 0) {
            "player-damage.newcomers.first-join-hours must be positive"
        }
        require(newcomerPlayTimeTicks > 0) {
            "player-damage.newcomers.play-time-hours must be positive"
        }
    }

    companion object {
        fun read(config: ConfigurationSection): PlayerDamageSettings = PlayerDamageSettings(
            creativeModeEnabled = config.getBoolean("player-damage.creative-mode", true),
            newcomersEnabled = config.getBoolean("player-damage.newcomers.enabled", false),
            newcomerFirstJoin = Duration.ofHours(config.getLong("player-damage.newcomers.first-join-hours", 48)),
            newcomerPlayTimeTicks = Math.multiplyExact(
                config.getLong("player-damage.newcomers.play-time-hours", 12), 20L * 60 * 60
            )
        )
    }
}
