package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import java.time.Clock

/** Captures maturation parameters at placement without changing health or live storage. */
class MaturingInitializer @JvmOverloads constructor(
    private val maturationDurationMillis: Long,
    private val initialDamageMultiplier: Double,
    private val clock: Clock = Clock.systemUTC()
) : CitadelInitializer {
    init {
        require(maturationDurationMillis > 0) { "Maturation duration must be positive" }
        require(initialDamageMultiplier.isFinite() && initialDamageMultiplier >= 1.0) {
            "Initial damage multiplier must be finite and at least one"
        }
    }

    override fun initialize(context: ActionContext, draft: CitadelDraft) {
        check(draft.getComponent(Maturing::class.java) == null) { "Maturation is already initialized" }
        draft.addComponent(Maturing::class.java,
            Maturing(clock.millis(), maturationDurationMillis, initialDamageMultiplier))
    }
}
