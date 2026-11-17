package ru.fornax.fornaxcitadel.modules.place

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionRegistration
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult

/**
 * Trusted operation after prerequisite validation and authorization. Initializers must only modify their draft.
 * Enabling registers placement prerequisites. Configure authorization separately when assembled without CitadelModule.
 * Direct calls do not notify action handlers.
 */
class PlaceService(
    private val actions: ActionManager,
    private val definitions: CitadelDefinitionRegistry
) : LifecycleService {
    private val manager = actions.componentManager
    private val registration = ActionRegistration(
        actions, PlaceProcessor(this), listOf(PlaceActionValidator(actions.citadels, definitions))
    )

    init {
        manager.registerComponent(CitadelKind.KEY, CitadelKind::class.java)
        manager.registerComponent(CitadelOwner.KEY, CitadelOwner::class.java)
    }

    override fun enable() = registration.enable()
    override fun disable() = registration.disable()

    fun place(action: PlaceAction): ActionResult {
        val definition = definitions.require(action.definitionKey)
        actions.citadels.createCitadel(action.position) { draft ->
            draft.addComponent(CitadelKind::class.java, CitadelKind(definition.key))
            action.context.actorId?.let { draft.addComponent(CitadelOwner::class.java, CitadelOwner(it)) }
            for (initializer in definition.initializers) initializer.initialize(action.context, draft)
        } ?: return ActionResult.DENIED
        return ActionResult.SUCCESS
    }
}
