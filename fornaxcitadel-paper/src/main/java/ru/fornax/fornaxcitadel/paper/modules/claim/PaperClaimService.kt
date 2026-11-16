package ru.fornax.fornaxcitadel.paper.modules.claim

import ru.fornax.fornaxcitadel.core.citadel.claimIfUnowned
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.claim.ClaimAction
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.paper.action.PlayerSource

/** Assigns ownership only. Item requirements belong to the interaction or enclosing action. */
class PaperClaimService(private val module: CitadelModule) : LifecycleService {
    private val validator = OwnerClaimValidator(module.citadels)
    private val processor = object : ActionProcessor<ClaimAction>() {
        override val actionClass = ClaimAction::class.java
        override val actionKey = ClaimAction.KEY
        override fun execute(action: ClaimAction) = claim(action)
    }
    private var enabled = false

    override fun enable() {
        if (enabled) return
        module.actions.processorCoordinator.registerProcessor(processor)
        try {
            module.actions.validatorCoordinator.registerValidator(validator)
            enabled = true
        } catch (failure: Throwable) {
            module.actions.processorCoordinator.unregisterProcessor(processor)
            throw failure
        }
    }

    override fun disable() {
        module.actions.validatorCoordinator.unregisterValidator(validator)
        module.actions.processorCoordinator.unregisterProcessor(processor)
        enabled = false
    }

    /** Executes an already validated claim; external requests must enter through module.actions. */
    fun claim(action: ClaimAction): ActionResult {
        if (!enabled) return ActionResult.DENIED
        val player = (action.context.source as? PlayerSource)?.player ?: return ActionResult.DENIED
        val citadel = module.citadels.findProtecting(action.position) ?: return ActionResult.DENIED
        return if (module.actions.componentManager.claimIfUnowned(citadel, player.uniqueId)) ActionResult.SUCCESS
        else ActionResult.DENIED
    }
}
