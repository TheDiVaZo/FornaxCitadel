package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle

internal fun placementInitializer(fill: (CitadelDraft) -> Unit) = object : CitadelInitializer {
    override fun initialize(draft: CitadelDraft) = fill(draft)
    override fun update(handle: CitadelHandle): Unit = error("Unexpected update in a placement test")
}
