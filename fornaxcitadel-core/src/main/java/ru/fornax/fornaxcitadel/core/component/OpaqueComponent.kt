package ru.fornax.fornaxcitadel.core.component

import ru.fornax.fornaxcitadel.core.util.Key

class OpaqueComponent(
    val key: Key,
    payload: ByteArray
) {
    private val payload = payload.copyOf()

    fun toByteArray(): ByteArray {
        return payload.copyOf()
    }
}
