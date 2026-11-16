package ru.fornax.fornaxcitadel.paper.modules.claim

import ru.fornax.fornaxcitadel.core.citadel.claimIfUnowned
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.claim.ClaimAction
import ru.fornax.fornaxcitadel.paper.action.ActionRegistration
import ru.fornax.fornaxcitadel.paper.action.PlayerSource

/** Assigns ownership only. Item requirements belong to the interaction or enclosing action. */
class PaperClaimService(private val module: CitadelModule) : LifecycleService {
    private val registration = ActionRegistration(
        module.actions, ClaimAction::class.java, ClaimAction.KEY, OwnerClaimValidator(module.citadels), ::claim
    )

    override fun enable() = registration.enable()
    override fun disable() = registration.disable()

    /** Executes an already validated claim; external requests must enter through module.actions. */
    fun claim(action: ClaimAction): ActionResult {
        if (!registration.isEnabled) return ActionResult.DENIED
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionResult.DENIED
        val citadel = module.citadels.findProtecting(action.position) ?: return ActionResult.DENIED
        return if (module.actions.componentManager.claimIfUnowned(citadel, player.uniqueId)) ActionResult.SUCCESS
        else ActionResult.DENIED
    }
}
