package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator

/** Enforces type damage rules or the separate explicit destruction policy. */
internal class DamagePolicyValidator(
    private val citadels: CitadelFacade,
    private val health: HealthService
) : ActionValidator<DamageAction> {
    override val actionClass = DamageAction::class.java

    override fun validate(action: DamageAction): ActionValidator.Result {
        val target = try {
            citadels.findProtecting(action.position)
        } catch (_: CitadelResolutionUnavailableException) {
            return ActionValidator.Result.Rejected
        } ?: return ActionValidator.Result.Neutral
        val allowed = when (action.mode) {
            DamageAction.Mode.DAMAGE -> health.allowsDamage(action.context, target)
            DamageAction.Mode.DESTROY -> health.allowsDestruction(action.context, target)
        }
        return if (allowed) ActionValidator.Result.Success
        else ActionValidator.Result.Rejected
    }
}
