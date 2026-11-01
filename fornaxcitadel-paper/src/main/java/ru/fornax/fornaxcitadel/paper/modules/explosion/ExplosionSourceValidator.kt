package ru.fornax.fornaxcitadel.paper.modules.explosion

import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.health.DamageAction

/** Source-to-target checks belong to the damage request, since the source has no target of its own. */
internal class ExplosionSourceValidator : ActionValidator<DamageAction> {
    override val actionClass = DamageAction::class.java

    override fun validate(action: DamageAction): ActionValidator.Result {
        val source = action.context.source as? ExplosionSource ?: return ActionValidator.Result.Neutral
        return if (source.position.worldId == action.citadelW) ActionValidator.Result.Success
        else ActionValidator.Result.Rejected
    }
}
