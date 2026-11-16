package ru.fornax.fornaxcitadel.paper.modules.repair

import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.paper.action.OwnerActionValidator

/** Requires an owner; other authorizers may grant access to another player's reinforcement. */
class OwnerRepairValidator(citadels: CitadelFacade) : ActionValidator<RepairAction> by
    OwnerActionValidator(RepairAction::class.java, citadels)
