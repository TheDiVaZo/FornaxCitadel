package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.component.ReadableComponent
import java.util.UUID

interface ReadableCitadelOwner : ReadableComponent {
    val playerId: UUID
}
