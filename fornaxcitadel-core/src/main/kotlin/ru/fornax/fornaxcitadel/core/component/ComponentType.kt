package ru.fornax.fornaxcitadel.core.component

data class ComponentType(
    val componentClass: Class<out Component>,
    val flag: Long,
    val id: Byte,
    var name: String? = null,
)