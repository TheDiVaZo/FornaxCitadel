package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.facade.ReadableHandle
import java.util.UUID

/** Ownership approval only; permissions and other action restrictions still apply. */
fun ReadableHandle.isOwnerOrUnowned(playerId: UUID): Boolean =
    isCurrent && getComponent(CitadelOwner::class.java).let { it == null || it.playerId == playerId }
