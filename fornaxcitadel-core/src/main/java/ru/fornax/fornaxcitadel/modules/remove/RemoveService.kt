package ru.fornax.fornaxcitadel.modules.remove

import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleNotifications
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult

/** Removes core data only. Minecraft block changes belong to a platform integration. */
class RemoveService @JvmOverloads constructor(
    private val actions: ActionManager,
    private val notifications: CitadelLifecycleNotifications,
    private val requestPolicy: RemovalPolicy = RemovalPolicy.DENY
) : LifecycleService {
    private val manager = actions.componentManager
    private val processor = RemoveProcessor(this)

    override fun enable() {
        actions.processorCoordinator.registerProcessor(processor)
    }

    override fun disable() {
        actions.processorCoordinator.unregisterProcessor(processor)
    }

    fun checkMutationAllowed() = notifications.checkMutationAllowed()

    fun request(action: RemoveAction): ActionResult {
        notifications.checkMutationAllowed()
        val citadel = try {
            actions.citadels.findProtecting(action.position)
        } catch (_: CitadelResolutionUnavailableException) {
            return ActionResult.DENIED
        } ?: return ActionResult.DENIED
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
