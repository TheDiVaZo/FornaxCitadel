package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput

/** Reinforcement level captured at placement, independent of later definition changes. */
class ReinforcementLevel(lvl: Int) : Component() {
    private constructor() : this(1)

    var lvl: Int = lvl
        set(value) {
            require(value > 0) { "Reinforcement level must be positive" }
            field = value
        }

    init {
        require(lvl > 0) { "Reinforcement level must be positive" }
    }

    override fun write(output: DataOutput) {
        output.writeInt(lvl)
    }

    override fun read(input: DataInput) {
        lvl = input.readInt()
    }

    companion object {
        @JvmField val KEY = Key("fornaxcitadel", "reinforcement_level")
    }
}
