package ru.fornax.fornaxcitadel.paper.config

import org.bukkit.configuration.ConfigurationSection
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageSettings
import java.time.Duration

object PlayerDamageSettingsReader {
    fun read(config: ConfigurationSection): PlayerDamageSettings = PlayerDamageSettings(
        creativeModeEnabled = config.getBoolean("player-damage.creative-mode", true),
        newcomersEnabled = config.getBoolean("player-damage.newcomers.enabled", false),
        newcomerFirstJoin = Duration.ofHours(config.getLong("player-damage.newcomers.first-join-hours", 48)),
        newcomerPlayTimeTicks = Math.multiplyExact(
            config.getLong("player-damage.newcomers.play-time-hours", 12), 20L * 60 * 60
        )
    )
}
