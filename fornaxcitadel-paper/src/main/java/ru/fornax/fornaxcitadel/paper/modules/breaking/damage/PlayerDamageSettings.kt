package ru.fornax.fornaxcitadel.paper.modules.breaking.damage

import java.time.Duration

/** Creative destruction and optional damage modifiers; ordinary hits start at one damage. */
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
}
