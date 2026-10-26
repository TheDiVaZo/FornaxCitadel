package ru.fornax.fornaxcitadel.core.component

import ru.fornax.fornaxcitadel.core.storage.ImmutablePayload
import ru.fornax.fornaxcitadel.core.util.Key

class OpaqueComponent internal constructor(
    val key: Key,
    internal val payloadData: ImmutablePayload
) {
    constructor(key: Key, payload: ByteArray) : this(
        key,
        ImmutablePayload.copyOf(payload)
    )

    fun toByteArray(): ByteArray {
        return payloadData.copy()
    }
}
