package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput
import java.util.UUID

/** The reinforcement's owner; absence means it is unowned. */
class CitadelOwner(override var playerId: UUID = UUID(0, 0)) :
    Component<ReadableCitadelOwner>(), ReadableCitadelOwner {
    override val readable: ReadableCitadelOwner get() = this

    override fun write(output: DataOutput) {
        writeUUID(playerId, output)
    }

    override fun read(input: DataInput) {
        playerId = readUUID(input)
    }

    companion object {
        @JvmField val KEY = Key("fornaxcitadel", "owner")
    }
}
