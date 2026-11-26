package ru.fornax.fornaxcitadel.modules.place

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult

/**
 * Trusted operation after prerequisite validation and authorization. Initializers must only modify their draft.
 * Direct calls do not notify action handlers.
 */
class PlaceService(
    private val definitions: CitadelDefinitionRegistry,
    private val citadels: CitadelFacade
) {
    fun place(action: PlaceAction): ActionResult {
        val definition = definitions.require(action.definitionKey)
        citadels.createCitadel(action.position) { draft ->
            action.context.actorId?.let { draft.addComponent(CitadelOwner::class.java, CitadelOwner(it)) }
            definition.initialize(draft)
        } ?: return ActionResult.DENIED
        return ActionResult.SUCCESS
    }
}
