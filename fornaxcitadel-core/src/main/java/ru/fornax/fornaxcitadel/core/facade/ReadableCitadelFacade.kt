package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import java.util.UUID

/** Resolves loaded reinforcement data without exposing creation or component mutation. */
interface ReadableCitadelFacade {
    fun getResponsiblePosition(position: CitadelPosition): CitadelPosition

    /** Null means no reinforcement; unavailable data is reported separately. */
    fun findProtecting(position: CitadelPosition): ReadableHandle?

    /** Visits stored citadels in the inclusive height range of a loaded chunk. */
    fun forEachCitadel(
        world: UUID,
        chunkX: Int,
        chunkZ: Int,
        minY: Int,
        maxY: Int,
        visit: (ReadableHandle) -> Unit
    )
}
