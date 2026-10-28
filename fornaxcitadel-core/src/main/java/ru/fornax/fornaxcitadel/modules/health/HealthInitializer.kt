package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

/** Adds initialized health and any supporting components to a placement draft. */
class HealthInitializer(private val behavior: HealthBehavior) : CitadelInitializer {
    override fun initialize(context: ActionContext, draft: CitadelDraft) {
        check(draft.getComponent(Health::class.java) == null) { "Health is already initialized" }
        val health = Health()
        behavior.initializeHealth(health, draft)
        require(health.maxHealth.isFinite() && health.maxHealth > 0.0) {
            "Initialized maximum health must be finite and positive"
        }
        require(health.health.isFinite() && health.health >= 0.0 && health.health <= health.maxHealth) {
            "Initialized health must be finite and between zero and its maximum"
        }
        draft.addComponent(Health::class.java, health)
    }
}
