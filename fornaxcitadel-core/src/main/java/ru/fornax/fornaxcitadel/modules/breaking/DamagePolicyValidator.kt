package ru.fornax.fornaxcitadel.modules.breaking

import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.health.DamageAction

/** Direct damage must honor the same type restrictions as breaking attempts. */
internal class DamagePolicyValidator(
    private val manager: SimpleComponentManager,
    private val breaking: BreakService
) : ActionValidator<DamageAction> {
    override val actionClass = DamageAction::class.java

    override fun validate(action: DamageAction): ActionValidator.Result {
        // The processor handles missing or unloaded targets.
        val target = manager.findCitadel(action.citadelW, action.citadelX, action.citadelY, action.citadelZ)
            ?: return ActionValidator.Result.Neutral
        return if (breaking.allowsDamage(action.context, target)) ActionValidator.Result.Success
        else ActionValidator.Result.Rejected
    }
}
