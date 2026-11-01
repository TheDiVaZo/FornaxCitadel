package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator

/** Enforces the configured type rule for DamageAction and every specialized damage action. */
internal class DamagePolicyValidator(
    private val manager: ComponentManager,
    private val health: HealthService
) : ActionValidator<DamageAction> {
    override val actionClass = DamageAction::class.java

    override fun validate(action: DamageAction): ActionValidator.Result {
        // The processor handles missing or unloaded targets.
        val target = manager.findCitadel(action.citadelW, action.citadelX, action.citadelY, action.citadelZ)
            ?: return ActionValidator.Result.Neutral
        return if (health.allowsDamage(action.context, target)) ActionValidator.Result.Success
        else ActionValidator.Result.Rejected
    }
}
