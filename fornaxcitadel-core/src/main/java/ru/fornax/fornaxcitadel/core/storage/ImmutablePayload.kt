package ru.fornax.fornaxcitadel.core.storage

import ru.fornax.fornaxcitadel.core.serialization.ReadAndWriteAble
import java.io.DataOutput
import java.io.IOException

internal class ImmutablePayload private constructor(
    private val data: ByteArray
) {
    val size: Int
        get() = data.size

    fun copy(): ByteArray = data.copyOf()

    @Throws(IOException::class)
    fun writeTo(output: DataOutput) {
        output.write(data)
    }

    @Throws(IOException::class)
    fun readInto(value: ReadAndWriteAble) {
        value.safeRead(data)
    }

    companion object {
        fun copyOf(data: ByteArray): ImmutablePayload {
            return ImmutablePayload(data.copyOf())
        }

        fun takeOwnership(data: ByteArray): ImmutablePayload {
            return ImmutablePayload(data)
        }
    }
}
