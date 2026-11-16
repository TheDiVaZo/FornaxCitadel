package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition

/** Resolves loaded reinforcement data without exposing creation or component mutation. */
interface ReadableCitadelFacade {
    fun getResponsiblePosition(position: CitadelPosition): CitadelPosition

    /** Null means no reinforcement; unavailable data is reported separately. */
    fun findProtecting(position: CitadelPosition): ReadableHandle?
}
