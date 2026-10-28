package ru.fornax.fornaxcitadel.modules.fortification

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

/** Persisted positive reinforcement level. A definition determines its supported upper bound. */
class Fortification(level: Int = 1) : Component() {
    var level: Int = level
        set(value) {
            require(value > 0) { "Fortification level must be positive: $value" }
            field = value
        }

    init {
        require(level > 0) { "Fortification level must be positive: $level" }
    }

    override fun write(output: DataOutput) {
        output.writeInt(level)
    }

    override fun read(input: DataInput) {
        val restoredLevel = input.readInt()
        if (restoredLevel <= 0) throw IOException("Invalid fortification level: $restoredLevel")
        level = restoredLevel
    }

    companion object {
        @JvmField
        val KEY = Key("fornaxcitadel", "fortification")
    }
}
