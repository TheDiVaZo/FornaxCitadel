package ru.fornax.fornaxcitadel.modules.place

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.ReadableCitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator

/** Checks placement prerequisites without granting authorization or loading chunks. */
class PlaceActionValidator(
    private val citadels: ReadableCitadelFacade,
    private val definitions: CitadelDefinitionRegistry
) : ActionValidator<PlaceAction> {
    override val actionClass = PlaceAction::class.java

    override fun validate(action: PlaceAction): ActionValidator.Result {
        val position = action.position
        if (!CitadelPosition.isSupported(position.x, position.y, position.z) ||
            definitions.find(action.definitionKey) == null) return ActionValidator.Result.Rejected
        return try {
            val responsible = citadels.getResponsiblePosition(position)
            if (citadels.findProtecting(position) != null ||
                (responsible != position && citadels.findProtecting(responsible) != null)) {
                ActionValidator.Result.Rejected
            } else ActionValidator.Result.Neutral
        } catch (_: CitadelResolutionUnavailableException) {
            ActionValidator.Result.Rejected
        }
    }
}
