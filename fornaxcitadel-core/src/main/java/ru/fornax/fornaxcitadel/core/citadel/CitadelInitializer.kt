package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

/** Prepares draft data only. External effects belong to the completed placement operation. */
fun interface CitadelInitializer {
    fun initialize(context: ActionContext, draft: CitadelDraft)
}
