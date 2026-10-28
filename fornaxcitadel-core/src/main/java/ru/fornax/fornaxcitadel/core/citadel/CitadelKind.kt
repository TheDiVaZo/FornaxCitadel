package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput

/** Persisted reference to gameplay rules; distinct from the registered class of a component. */
class CitadelKind(var definitionKey: Key = Key("fornaxcitadel", "unknown")) : Component() {
    override fun write(output: DataOutput) {
        output.writeUTF(definitionKey.namespace)
        output.writeUTF(definitionKey.key)
    }

    override fun read(input: DataInput) {
        definitionKey = Key(input.readUTF(), input.readUTF())
    }

    companion object {
        @JvmField
        val KEY = Key("fornaxcitadel", "kind")
    }
}
