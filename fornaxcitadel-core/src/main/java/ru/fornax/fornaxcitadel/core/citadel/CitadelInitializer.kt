package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle

/** Manages type-specific components during placement and changes of type. */
interface CitadelInitializer {
    /** Adds components to the draft before placement commits it. */
    fun initialize(draft: CitadelDraft)

    /** Updates live components in place. Changes made before an exception remain visible. */
    fun update(handle: CitadelHandle)
}
