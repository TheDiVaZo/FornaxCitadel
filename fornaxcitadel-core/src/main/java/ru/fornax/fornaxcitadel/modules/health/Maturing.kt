package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput

/**
 * Persisted maturation parameters captured at placement. Start time uses Unix epoch milliseconds.
 * Progress is derived from the clock without updating this component or Health, including while unloaded.
 */
class Maturing(
    var startedAtMillis: Long = 0L,
    var maturationDurationMillis: Long = 1L,
    var initialDamageMultiplier: Double = 1.0
) : Component() {
    init {
        validate()
    }

    internal fun validate() {
        require(maturationDurationMillis > 0) { "Maturation duration must be positive" }
        require(initialDamageMultiplier.isFinite() && initialDamageMultiplier >= 1.0) {
            "Initial damage multiplier must be finite and at least one"
        }
    }

    override fun write(output: DataOutput) {
        validate()
        output.writeLong(startedAtMillis)
        output.writeLong(maturationDurationMillis)
        output.writeDouble(initialDamageMultiplier)
    }

    override fun read(input: DataInput) {
        startedAtMillis = input.readLong()
        maturationDurationMillis = input.readLong()
        initialDamageMultiplier = input.readDouble()
        validate()
    }

    companion object {
        @JvmField
        val KEY = Key("fornaxcitadel", "maturing")
    }
}
