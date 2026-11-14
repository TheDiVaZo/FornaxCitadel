package ru.fornax.fornaxcitadel.modules.place

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleNotifications
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.citadel.ReinforcementItem
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult

/**
 * Trusted operation after prerequisite validation and authorization. Initializers must only modify their draft.
 * When assembled without CitadelModule, register prerequisite and authorization validators separately.
 */
class PlaceService(
    private val actions: ActionManager,
    private val definitions: CitadelDefinitionRegistry,
    private val notifications: CitadelLifecycleNotifications
) : LifecycleService {
    private val manager = actions.componentManager
    private val processor = PlaceProcessor(this)

    init {
        manager.registerComponent(CitadelKind.KEY, CitadelKind::class.java)
        manager.registerComponent(CitadelOwner.KEY, CitadelOwner::class.java)
        manager.registerComponent(ReinforcementItem.KEY, ReinforcementItem::class.java)
    }

    override fun enable() {
        actions.processorCoordinator.registerProcessor(processor)
    }

    override fun disable() {
        actions.processorCoordinator.unregisterProcessor(processor)
    }

    fun place(action: PlaceAction): ActionResult {
        notifications.checkMutationAllowed()
        val definition = definitions.require(action.definitionKey)
        val placed = actions.citadels.createCitadel(action.position) { draft ->
            draft.addComponent(CitadelKind::class.java, CitadelKind(definition.key))
            action.context.actorId?.let { draft.addComponent(CitadelOwner::class.java, CitadelOwner(it)) }
            action.reinforcementItemKey?.let {
                draft.addComponent(ReinforcementItem::class.java, ReinforcementItem(it))
            }
            for (initializer in definition.initializers) initializer.initialize(action.context, draft)
        } ?: return ActionResult.DENIED
        notifications.notifyPlaced(placed)
        return ActionResult.SUCCESS
    }
}
