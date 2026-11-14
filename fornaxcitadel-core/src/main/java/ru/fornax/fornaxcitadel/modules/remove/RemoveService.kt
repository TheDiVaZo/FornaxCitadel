package ru.fornax.fornaxcitadel.modules.remove

import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult

/**
 * Removes core data after authorization. Minecraft block changes belong to a platform integration.
 * When assembled without CitadelModule, configure action metadata, prerequisite and authorization validators.
 * Direct calls do not notify action handlers.
 */
class RemoveService(
    private val actions: ActionManager
) : LifecycleService {
    private val manager = actions.componentManager
    private val processor = RemoveProcessor(this)

    override fun enable() {
        actions.processorCoordinator.registerProcessor(processor)
    }

    override fun disable() {
        actions.processorCoordinator.unregisterProcessor(processor)
    }

    /** Executes a validated request with an available target. External requests must enter through actions. */
    fun request(action: RemoveAction): ActionResult {
        val citadel = checkNotNull(actions.citadels.findProtecting(action.position)) {
            "Validated removal target is unavailable"
        }
        return remove(citadel)
    }

    /** Trusted completion after checks; does not re-run cancelable action validation. */
    fun remove(citadel: CitadelHandle): ActionResult {
        if (!manager.removeCitadel(citadel)) return ActionResult.DENIED
        return ActionResult.SUCCESS
    }

    /** Trusted batch completion. Each handle is checked independently; stale handles are skipped. */
    fun removeAll(citadels: Iterable<CitadelHandle>): Int {
        var removed = 0
        for (citadel in citadels) if (remove(citadel) == ActionResult.SUCCESS) removed++
        return removed
    }
}
