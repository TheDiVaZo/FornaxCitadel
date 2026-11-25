package ru.fornax.fornaxcitadel.core.resolver

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition

/**
 * Resolves a world position without changing the original action. Implementations are read-only,
 * run on the owning game thread, and must never load chunks as a side effect of a lookup.
 * Missing data must throw [CitadelResolutionUnavailableException], not report absent protection.
 */
interface CitadelResolver {
    fun getResponsiblePosition(position: CitadelPosition): CitadelPosition

    /** Yields positions in lookup order. The facade stops at the first stored citadel. */
    fun getProtectionCandidates(position: CitadelPosition): Sequence<CitadelPosition>
}
