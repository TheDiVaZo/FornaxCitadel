package ru.fornax.fornaxcitadel.paper.modules.repair

import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.paper.action.OwnerActionValidator

/** Grants repair to the owner or an unowned target; other authorizers may grant access when this one abstains. */
class OwnerRepairValidator(citadels: CitadelFacade) : ActionValidator<RepairAction> by
    OwnerActionValidator(RepairAction::class.java, citadels)
