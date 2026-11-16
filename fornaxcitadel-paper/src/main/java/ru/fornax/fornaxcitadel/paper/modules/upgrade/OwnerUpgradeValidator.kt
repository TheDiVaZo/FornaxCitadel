package ru.fornax.fornaxcitadel.paper.modules.upgrade

import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.action.OwnerActionValidator

/** Requires an owner; explicit external grants and vetoes follow normal action rules. */
class OwnerUpgradeValidator(citadels: CitadelFacade) : ActionValidator<UpgradeAction> by
    OwnerActionValidator(UpgradeAction::class.java, citadels, ActionValidator.Result.Rejected)
