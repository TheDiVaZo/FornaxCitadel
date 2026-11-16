package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.component.ReadableComponent

interface ReadableMaturing : ReadableComponent {
    val startedAtMillis: Long
}
