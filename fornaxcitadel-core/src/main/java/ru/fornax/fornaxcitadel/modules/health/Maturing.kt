package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput

/** Persisted maturation start time in Unix epoch milliseconds. */
class Maturing(var startedAtMillis: Long = 0L) : Component() {
    override fun write(output: DataOutput) {
        output.writeLong(startedAtMillis)
    }

    override fun read(input: DataInput) {
        startedAtMillis = input.readLong()
    }

    companion object {
        @JvmField
        val KEY = Key("fornaxcitadel", "maturing")
    }
}
