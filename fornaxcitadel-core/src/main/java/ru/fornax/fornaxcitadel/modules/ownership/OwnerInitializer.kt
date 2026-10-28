package ru.fornax.fornaxcitadel.modules.ownership

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

class OwnerInitializer : CitadelInitializer {
    override fun initialize(context: ActionContext, draft: CitadelDraft) {
        val owner = requireNotNull(context.actorId) { "An owned citadel requires a player" }
        draft.addComponent(Owner::class.java, Owner(owner))
    }
}
