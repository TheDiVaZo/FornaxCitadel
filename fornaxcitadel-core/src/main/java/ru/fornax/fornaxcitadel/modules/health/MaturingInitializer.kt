package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import java.time.Clock

/** Starts maturation on placement and upgrade, including for types with zero duration. */
class MaturingInitializer @JvmOverloads constructor(
    private val clock: Clock = Clock.systemUTC()
) : CitadelInitializer {
    override fun initialize(draft: CitadelDraft) {
        check(draft.getComponent(Maturing::class.java) == null) { "Maturation is already initialized" }
        draft.addComponent(Maturing::class.java, Maturing(clock.millis()))
    }

    override fun update(handle: CitadelHandle) {
        handle.putComponent(Maturing::class.java, Maturing(clock.millis()))
    }
}
