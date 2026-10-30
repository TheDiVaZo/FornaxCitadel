package ru.fornax.fornaxcitadel.paper.modules.breaking.damage.modifiers

import org.bukkit.Statistic
import org.bukkit.entity.Player
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifier
import java.time.Clock
import java.time.Duration
import kotlin.math.max
import kotlin.math.round

/**
 * Restores damage from 10% to 100% as either account age or play time reaches its threshold.
 * The old maxDamageFactor cancels out algebraically, so it is intentionally omitted here.
 */
class NerfingNewcomersModifier @JvmOverloads constructor(
    timeSinceFirstJoin: Duration,
    private val playTimeSince: Long,
    private val clock: Clock = Clock.systemUTC()
) : PlayerDamageModifier {
    private val firstJoinMillis = timeSinceFirstJoin.toMillis()

    init {
        require(firstJoinMillis > 0) { "Time since first join must be at least one positive millisecond" }
        require(playTimeSince > 0) { "Play time threshold must be positive" }
    }

    override fun getDamage(player: Player, citadel: CitadelHandle, currentDamage: Double): Double {
        val ageProgress = (clock.millis().toDouble() - player.firstPlayed.toDouble()) / firstJoinMillis.toDouble()
        val playProgress = player.getStatistic(Statistic.PLAY_ONE_MINUTE).toDouble() / playTimeSince.toDouble()
        val progress = max(ageProgress, playProgress).coerceIn(0.1, 1.0)
        val adjusted = currentDamage * progress
        val scaled = adjusted * 100.0
        return if (scaled.isFinite()) round(scaled) / 100.0 else adjusted
    }
}
