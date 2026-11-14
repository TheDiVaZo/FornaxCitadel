package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction

/** Installs one test rule directly in the action pipeline, without a service policy registry. */
internal fun ActionManager.validateDamage(
    definitionKey: Key? = null,
    mode: DamageAction.Mode = DamageAction.Mode.DAMAGE,
    allowed: (ActionContext, CitadelHandle) -> Boolean = { _, _ -> true }
): ActionValidator<DamageAction> {
    val validator = object : ActionValidator<DamageAction> {
        override val actionClass = DamageAction::class.java

        override fun validate(action: DamageAction): ActionValidator.Result {
            if (action.mode != mode) return ActionValidator.Result.Neutral
            val target = try {
                citadels.findProtecting(action.position)
            } catch (_: CitadelResolutionUnavailableException) {
                return ActionValidator.Result.Rejected
            } ?: return ActionValidator.Result.Neutral
            if (definitionKey != null && target.getComponent(CitadelKind::class.java)?.definitionKey != definitionKey) {
                return ActionValidator.Result.Neutral
            }
            return if (allowed(action.context, target)) ActionValidator.Result.Success
            else ActionValidator.Result.Rejected
        }
    }
    validatorCoordinator.registerValidator(validator)
    return validator
}

internal fun ActionManager.validateRemoval(
    allowed: (ActionContext, CitadelHandle) -> Boolean = { _, _ -> true }
): ActionValidator<RemoveAction> {
    val validator = object : ActionValidator<RemoveAction> {
        override val actionClass = RemoveAction::class.java

        override fun validate(action: RemoveAction): ActionValidator.Result {
            val target = try {
                citadels.findProtecting(action.position)
            } catch (_: CitadelResolutionUnavailableException) {
                return ActionValidator.Result.Rejected
            } ?: return ActionValidator.Result.Neutral
            return if (allowed(action.context, target)) ActionValidator.Result.Success
            else ActionValidator.Result.Rejected
        }
    }
    validatorCoordinator.registerValidator(validator)
    return validator
}
