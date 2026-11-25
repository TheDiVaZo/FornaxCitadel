package ru.fornax.fornaxcitadel.modules.remove

import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult

/**
 * Removes core data after authorization. Minecraft block changes belong to a platform integration.
 * Direct calls do not notify action handlers.
 */
class RemoveService(private val citadels: CitadelFacade) {
    /** Executes a validated request with an available target. External requests must enter through actions. */
    fun request(action: RemoveAction): ActionResult {
        val citadel = checkNotNull(citadels.findProtecting(action.position)) {
            "Validated removal target is unavailable"
        }
        return remove(citadel)
    }

    /** Trusted completion after checks; does not re-run cancelable action validation. */
    fun remove(citadel: CitadelHandle): ActionResult {
        if (!citadels.removeCitadel(citadel)) return ActionResult.DENIED
        return ActionResult.SUCCESS
    }

    /** Trusted batch completion. Each handle is checked independently; stale handles are skipped. */
    fun removeAll(citadels: Iterable<CitadelHandle>): Int {
        var removed = 0
        for (citadel in citadels) if (remove(citadel) == ActionResult.SUCCESS) removed++
        return removed
    }
}
