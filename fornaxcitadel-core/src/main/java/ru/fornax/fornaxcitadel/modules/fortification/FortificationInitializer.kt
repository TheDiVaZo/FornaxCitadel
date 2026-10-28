package ru.fornax.fornaxcitadel.modules.fortification

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext

/** Register before the health initializer when health depends on reinforcement level. */
class FortificationInitializer @JvmOverloads constructor(private val initialLevel: Int = 1) : CitadelInitializer {
    init {
        require(initialLevel > 0) { "Initial fortification level must be positive: $initialLevel" }
    }

    override fun initialize(context: ActionContext, draft: CitadelDraft) {
        draft.addComponent(Fortification::class.java, Fortification(initialLevel))
    }
}
