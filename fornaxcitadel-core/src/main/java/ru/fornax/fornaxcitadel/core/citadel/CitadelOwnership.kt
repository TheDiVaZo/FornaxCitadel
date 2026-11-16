package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import java.util.UUID

/** Ownership approval only; permissions and other action restrictions still apply. */
fun CitadelHandle.isOwnerOrUnowned(playerId: UUID): Boolean =
    isCurrent && getComponent(CitadelOwner::class.java).let { it == null || it.playerId == playerId }

/** Called when committing an authorized action on the game thread. Never replaces an existing owner. */
fun ComponentManager.claimIfUnowned(citadel: CitadelHandle, playerId: UUID): Boolean {
    if (!isCurrent(citadel) || citadel.getComponent(CitadelOwner::class.java) != null) return false
    putComponent(CitadelOwner::class.java, citadel.world, citadel.x, citadel.y, citadel.z, CitadelOwner(playerId))
    return true
}
