package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.component.ReadableComponent
import ru.fornax.fornaxcitadel.core.util.Key

interface ReadableCitadelKind : ReadableComponent {
    val definitionKey: Key
}
