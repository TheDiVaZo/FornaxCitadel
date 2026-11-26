package ru.fornax.fornaxcitadel.paper.modules.acid

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.modules.acid.AcidAction

/** Captures the detailed result of one synchronous call for player feedback. */
internal class AcidActivationRequest(context: ActionContext, position: CitadelPosition) : AcidAction(context, position) {
    var result: AcidResult = AcidResult.Failed(AcidFailure.DENIED)
}
