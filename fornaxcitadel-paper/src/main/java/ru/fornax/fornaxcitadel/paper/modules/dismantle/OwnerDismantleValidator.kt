package ru.fornax.fornaxcitadel.paper.modules.dismantle

import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.paper.action.OwnerActionValidator

/** Grants dismantling to the owner or an unowned target; other authorizers may grant access when this one abstains. */
class OwnerDismantleValidator(citadels: CitadelFacade) : ActionValidator<DismantleAction> by
    OwnerActionValidator(DismantleAction::class.java, citadels)
