package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput

/** Material identity captured at placement, independent of later definition configuration changes. */
class ReinforcementItem(var materialKey: Key = Key("fornaxcitadel", "unknown")) : Component() {
    override fun write(output: DataOutput) {
        output.writeUTF(materialKey.namespace)
        output.writeUTF(materialKey.key)
    }

    override fun read(input: DataInput) {
        materialKey = Key(input.readUTF(), input.readUTF())
    }

    companion object {
        @JvmField val KEY = Key("fornaxcitadel", "reinforcement_item")
    }
}
