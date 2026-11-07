package ru.fornax.fornaxcitadel.modules.place

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleNotifications
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult

/** Trusted operation after authorization. Initializers must only modify their draft. */
class PlaceService(
    private val actions: ActionManager,
    private val definitions: CitadelDefinitionRegistry,
    private val notifications: CitadelLifecycleNotifications
) : LifecycleService {
    private val manager = actions.componentManager
    private val processor = PlaceProcessor(this)

    init {
        manager.registerComponent(CitadelKind.KEY, CitadelKind::class.java)
    }

    override fun enable() {
        actions.processorCoordinator.registerProcessor(processor)
    }

    override fun disable() {
        actions.processorCoordinator.unregisterProcessor(processor)
    }

    fun place(action: PlaceAction): ActionResult {
        notifications.checkMutationAllowed()
        val definition = definitions.find(action.definitionKey) ?: return ActionResult.DENIED
        val placed = actions.citadels.createCitadel(action.position) { draft ->
            draft.addComponent(CitadelKind::class.java, CitadelKind(definition.key))
            for (initializer in definition.initializers) initializer.initialize(action.context, draft)
        } ?: return ActionResult.DENIED
        notifications.notifyPlaced(placed)
        return ActionResult.SUCCESS
    }
}
