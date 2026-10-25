package ru.fornax.fornaxcitadel.core.util

class InvalidKeyException(
    val keyNamespace: String,
    val keyValue: String,
    message: String
) : RuntimeException(message)
