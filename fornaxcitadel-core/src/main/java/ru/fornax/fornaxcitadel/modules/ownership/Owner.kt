package ru.fornax.fornaxcitadel.modules.ownership

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput
import java.util.UUID

class Owner(var playerId: UUID = UUID(0L, 0L)) : Component() {
    override fun write(output: DataOutput) {
        output.writeLong(playerId.mostSignificantBits)
        output.writeLong(playerId.leastSignificantBits)
    }
    override fun read(input: DataInput) { playerId = UUID(input.readLong(), input.readLong()) }
    companion object {
        @JvmField val KEY = Key("fornaxcitadel", "owner")
    }
}
