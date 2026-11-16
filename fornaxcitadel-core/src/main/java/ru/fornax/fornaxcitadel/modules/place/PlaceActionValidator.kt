package ru.fornax.fornaxcitadel.modules.place

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator

/** Checks placement prerequisites without granting authorization or loading chunks. */
class PlaceActionValidator(
    private val citadels: CitadelFacade,
    private val definitions: CitadelDefinitionRegistry
) : ActionValidator<PlaceAction> {
    override val actionClass = PlaceAction::class.java

    override fun validate(action: PlaceAction): ActionValidator.Result {
        if (!CitadelPosition.isSupported(action.citadelX, action.citadelY, action.citadelZ) ||
            definitions.find(action.definitionKey) == null) return ActionValidator.Result.Rejected
        return try {
            val responsible = citadels.getResponsiblePosition(action.position)
            if (citadels.findProtecting(action.position) != null ||
                (responsible != action.position && citadels.findProtecting(responsible) != null)) {
                ActionValidator.Result.Rejected
            } else ActionValidator.Result.Neutral
        } catch (_: CitadelResolutionUnavailableException) {
            ActionValidator.Result.Rejected
        }
    }
}
