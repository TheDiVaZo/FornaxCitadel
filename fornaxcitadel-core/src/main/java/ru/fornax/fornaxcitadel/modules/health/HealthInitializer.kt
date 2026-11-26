package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle

/** Sets full health from the current profile and clears saved health for types without a profile. */
class HealthInitializer(private val service: HealthService) : CitadelInitializer {
    override fun initialize(draft: CitadelDraft) {
        check(draft.getComponent(Health::class.java) == null) { "Health is already initialized" }
        val key = draft.getComponent(CitadelKind::class.java)?.definitionKey ?: return
        val maximum = service.findDefinition(key)?.maxHealth ?: return
        draft.addComponent(Health::class.java, Health(maximum))
    }

    override fun update(handle: CitadelHandle) {
        val maximum = service.maxHealth(handle)
        if (maximum == null) {
            handle.removeComponent(Health::class.java)
        } else {
            handle.putComponent(Health::class.java, Health(maximum))
        }
    }
}
