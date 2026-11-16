package ru.fornax.fornaxcitadel.testing

import ru.fornax.fornaxcitadel.core.component.ReadableComponent

/** Shared read contract for integer components used by storage and facade fixtures. */
internal interface ReadableTestValue : ReadableComponent {
    val value: Int
}
