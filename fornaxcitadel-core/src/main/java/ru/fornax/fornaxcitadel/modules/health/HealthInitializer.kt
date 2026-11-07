package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

/** Creates full health with a maximum that does not change with time. */
class HealthInitializer(private val maxHealth: Double) : CitadelInitializer {
    init {
        require(maxHealth.isFinite() && maxHealth > 0.0) {
            "Maximum health must be finite and positive"
        }
    }

    override fun initialize(context: ActionContext, draft: CitadelDraft) {
        check(draft.getComponent(Health::class.java) == null) { "Health is already initialized" }
        draft.addComponent(Health::class.java, Health(maxHealth, maxHealth))
    }
}
