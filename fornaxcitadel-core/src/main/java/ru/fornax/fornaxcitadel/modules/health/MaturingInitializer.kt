package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import java.time.Clock

/** Captures placement time even for types whose current maturation duration is zero. */
class MaturingInitializer @JvmOverloads constructor(
    private val clock: Clock = Clock.systemUTC()
) : CitadelInitializer {
    override fun initialize(context: ActionContext, draft: CitadelDraft) {
        check(draft.getComponent(Maturing::class.java) == null) { "Maturation is already initialized" }
        draft.addComponent(Maturing::class.java, Maturing(clock.millis()))
    }
}
