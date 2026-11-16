package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.facade.ReadableHandle
import ru.fornax.fornaxcitadel.core.util.Key
import java.time.Clock
import java.util.logging.Logger

/** Resolves current settings by CitadelKind without modifying components or scheduling work. */
class MaturationService @JvmOverloads constructor(
    private val clock: Clock = Clock.systemUTC(),
    private val logger: Logger = Logger.getLogger(MaturationService::class.java.name)
) {
    private val definitions = mutableMapOf<Key, MaturationDefinition>()
    private val warnedKeys = mutableSetOf<Key?>()
    private var multipliers: List<MaturationMultiplier> = emptyList()

    /** Replaces this key's settings, including for citadels already placed. */
    fun registerDefinition(key: Key, definition: MaturationDefinition) {
        definitions[key] = definition
        warnedKeys.remove(key)
    }

    fun unregisterDefinition(key: Key): Boolean = definitions.remove(key) != null

    fun findDefinition(key: Key): MaturationDefinition? = definitions[key]

    /** Duplicate instances are ignored; each calculation retains its registration snapshot. */
    fun registerMultiplier(multiplier: MaturationMultiplier) {
        if (multipliers.none { it === multiplier }) multipliers = multipliers + multiplier
    }

    fun unregisterMultiplier(multiplier: MaturationMultiplier): Boolean {
        if (multipliers.none { it === multiplier }) return false
        multipliers = multipliers.filterNot { it === multiplier }
        return true
    }

    internal fun definition(citadel: ReadableHandle): MaturationDefinition? {
        val key = citadel.getComponent(CitadelKind::class.java)?.definitionKey
        return definitions[key] ?: run {
            if (warnedKeys.add(key)) logger.warning(
                "Unknown maturation definition '${key ?: "<missing CitadelKind>"}'; treating citadels as mature"
            )
            null
        }
    }

    fun progress(citadel: ReadableHandle): Double {
        if (citadel.getComponent(Maturing::class.java) == null) return 1.0
        return progress(citadel, definition(citadel) ?: return 1.0)
    }

    internal fun progress(citadel: ReadableHandle, definition: MaturationDefinition): Double {
        val maturing = citadel.getComponent(Maturing::class.java) ?: return 1.0
        if (definition.duration.isZero) return 1.0
        // Converting seconds/nanos separately avoids Duration.toMillis()/toNanos() overflow.
        var duration = definition.duration.seconds.toDouble() * 1000.0 + definition.duration.nano / 1_000_000.0
        for (rule in multipliers) {
            val multiplier = rule.getMultiplier(citadel)
            require(multiplier.isFinite() && multiplier > 0.0) { "Maturation multiplier must be finite and positive" }
            duration *= multiplier
        }
        val now = clock.millis()
        if (now <= maturing.startedAtMillis) return 0.0
        val difference = now - maturing.startedAtMillis
        val elapsed = if (difference >= 0) difference.toDouble()
            else now.toDouble() - maturing.startedAtMillis.toDouble()
        // Infinity represents a duration longer than the clock can express, never instant maturity.
        return (elapsed / duration).coerceIn(0.0, 1.0)
    }

    fun isMature(citadel: ReadableHandle): Boolean = progress(citadel) >= 1.0
}
