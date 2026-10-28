package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle

/** Prepares health in a draft and updates live health on the owning game thread. */
interface HealthBehavior {
    /** Returns true if health changed; the caller must mark it dirty or use an edit operation. */
    fun updateHealth(
        component: Health,
        citadel: CitadelHandle
    ): Boolean { return false }

    /** Prepares health and supporting components without publishing a citadel to storage. */
    fun initializeHealth(component: Health, draft: CitadelDraft)
}
