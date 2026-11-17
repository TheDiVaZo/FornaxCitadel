package ru.fornax.fornaxcitadel.paper.modules.upgrade

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.facade.ReadableCitadelFacade
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.ExistingCitadelValidator
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction

/** Checks upgrade prerequisites without granting authorization or loading chunks. */
class UpgradeActionValidator(
    citadels: ReadableCitadelFacade,
    private val definitions: CitadelDefinitionRegistry
) : ActionValidator<UpgradeAction> {
    override val actionClass = UpgradeAction::class.java
    private val targetValidator = ExistingCitadelValidator(actionClass, citadels)

    override fun validate(action: UpgradeAction): ActionValidator.Result {
        if (definitions.find(action.definitionKey) == null) {
            return ActionValidator.Result.Rejected
        }
        return targetValidator.validate(action)
    }
}
