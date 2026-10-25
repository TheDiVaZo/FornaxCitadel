package ru.fornax.fornaxcitadel.core.component

import ru.fornax.fornaxcitadel.core.util.Key

class ComponentType<T : Component> internal constructor(
    val key: Key,
    val componentClass: Class<T>,
    val flag: Long,
    val id: Byte
)
