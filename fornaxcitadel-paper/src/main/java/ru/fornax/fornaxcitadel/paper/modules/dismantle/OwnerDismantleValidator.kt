package ru.fornax.fornaxcitadel.paper.modules.dismantle

import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.paper.action.OwnerActionValidator

/** Requires an owner; other authorizers may grant access to another player's reinforcement. */
class OwnerDismantleValidator(citadels: CitadelFacade) : ActionValidator<DismantleAction> by
    OwnerActionValidator(DismantleAction::class.java, citadels)
