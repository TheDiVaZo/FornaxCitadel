package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput

/**
 * Only the placement time is persisted, in Unix epoch milliseconds.
 * The definition key is already stored in CitadelKind; its current settings determine progress.
 */
class Maturing(override var startedAtMillis: Long = 0L) : Component<ReadableMaturing>(), ReadableMaturing {
    override val readable: ReadableMaturing get() = this

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
