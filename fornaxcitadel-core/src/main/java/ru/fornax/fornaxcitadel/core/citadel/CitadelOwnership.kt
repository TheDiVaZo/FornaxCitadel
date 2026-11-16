package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import java.util.UUID

/** Ownership approval only; permissions and other action restrictions still apply. */
fun CitadelHandle.isOwnerOrUnowned(playerId: UUID): Boolean =
    isCurrent && getComponent(CitadelOwner::class.java).let { it == null || it.playerId == playerId }
