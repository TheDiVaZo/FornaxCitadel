package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

/** Creates full health from the current profile, or leaves health absent when no profile exists. */
class HealthInitializer(private val service: HealthService) : CitadelInitializer {
    override fun initialize(context: ActionContext, draft: CitadelDraft) {
        check(draft.getComponent(Health::class.java) == null) { "Health is already initialized" }
        val key = draft.getComponent(CitadelKind::class.java)?.definitionKey ?: return
        val definition = service.findDefinition(key) ?: return
        draft.addComponent(Health::class.java, Health(definition.maxHealth))
    }
}
