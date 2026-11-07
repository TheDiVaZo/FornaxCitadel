package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition

/**
 * Resolves a world position without changing the original action. Implementations are read-only,
 * run on the owning game thread, and must never load chunks as a side effect of a lookup.
 * Missing data must throw [CitadelResolutionUnavailableException], not report absent protection.
 */
interface CitadelResolver {
    fun getResponsiblePosition(position: CitadelPosition): CitadelPosition
    fun getReinforcementProtecting(position: CitadelPosition): CitadelHandle?
}

/** Coordinate-only behavior for platforms that do not provide linked block rules. */
class DirectCitadelResolver(private val manager: ComponentManager) : CitadelResolver {
    override fun getResponsiblePosition(position: CitadelPosition): CitadelPosition = position

    override fun getReinforcementProtecting(position: CitadelPosition): CitadelHandle? {
        if (!CitadelPosition.isSupported(position.x, position.y, position.z)) return null
        if (!manager.isChunkLoaded(position.world, position.x shr 4, position.z shr 4)) {
            throw CitadelResolutionUnavailableException(position)
        }
        return manager.findCitadel(position.world, position.x, position.y, position.z)
    }
}
