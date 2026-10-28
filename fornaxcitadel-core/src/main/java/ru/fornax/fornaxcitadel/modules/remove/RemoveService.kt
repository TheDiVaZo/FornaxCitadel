package ru.fornax.fornaxcitadel.modules.remove

import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleNotifications
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult

/** Removes core data only. Minecraft block changes belong to a platform integration. */
class RemoveService @JvmOverloads constructor(
    private val manager: SimpleComponentManager,
    private val notifications: CitadelLifecycleNotifications,
    private val requestPolicy: RemovalPolicy = RemovalPolicy.DENY
) {
    fun checkMutationAllowed() = notifications.checkMutationAllowed()

    fun request(action: RemoveAction): ActionResult {
        notifications.checkMutationAllowed()
        if (!manager.isChunkLoaded(action.citadelW, action.citadelX shr 4, action.citadelZ shr 4)) {
            return ActionResult.DENIED
        }
        val citadel = manager.findCitadel(action.citadelW, action.citadelX, action.citadelY, action.citadelZ)
            ?: return ActionResult.DENIED
        if (!requestPolicy.mayRemove(action.context, citadel)) return ActionResult.DENIED
        return remove(citadel, RemovalCause.ADMIN)
    }

    /** Trusted completion after checks; does not re-run cancelable action validation. */
    fun remove(citadel: CitadelHandle, cause: RemovalCause): ActionResult {
        notifications.checkMutationAllowed()
        if (!manager.removeCitadel(citadel)) return ActionResult.DENIED
        notifications.notifyRemoved(citadel, cause)
        return ActionResult.SUCCESS
    }

    /** Trusted batch completion. Each handle is checked independently; stale handles are skipped. */
    fun removeAll(citadels: Iterable<CitadelHandle>, cause: RemovalCause): Int {
        notifications.checkMutationAllowed()
        var removed = 0
        for (citadel in citadels) if (remove(citadel, cause) == ActionResult.SUCCESS) removed++
        return removed
    }
}
