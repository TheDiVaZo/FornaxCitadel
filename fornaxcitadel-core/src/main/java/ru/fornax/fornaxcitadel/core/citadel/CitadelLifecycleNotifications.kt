package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import java.util.logging.Level
import java.util.logging.Logger

/** Synchronous owner-thread observers. No event object is allocated for each notification. */
class CitadelLifecycleNotifications @JvmOverloads constructor(
    private val failureHandler: (Exception) -> Unit = { LOG.log(Level.WARNING, "Citadel observer failed", it) }
) {
    private var listeners = emptyArray<CitadelLifecycleListener>()
    var isDispatching: Boolean = false
        private set

    fun addListener(listener: CitadelLifecycleListener) {
        if (listeners.none { it === listener }) listeners = listeners + listener
    }

    fun removeListener(listener: CitadelLifecycleListener) {
        listeners = listeners.filterNot { it === listener }.toTypedArray()
    }

    fun checkMutationAllowed() {
        check(!isDispatching) { "Do not place, remove, or damage citadels from observers; post an action instead" }
    }

    internal fun notifyPlaced(citadel: CitadelHandle) {
        checkMutationAllowed()
        isDispatching = true
        try {
            for (listener in listeners) {
                try {
                    listener.onPlaced(citadel.position, citadel.runtimeId)
                } catch (failure: Exception) {
                    report(failure)
                }
            }
        } finally { isDispatching = false }
    }

    internal fun notifyRemoved(citadel: CitadelHandle, cause: RemovalCause) {
        checkMutationAllowed()
        isDispatching = true
        try {
            for (listener in listeners) {
                try {
                    listener.onRemoved(citadel.position, citadel.runtimeId, cause)
                } catch (failure: Exception) {
                    report(failure)
                }
            }
        } finally { isDispatching = false }
    }

    private fun report(failure: Exception) {
        try {
            failureHandler(failure)
        } catch (reportFailure: Exception) {
            LOG.log(Level.WARNING, "Citadel observer error handler failed", reportFailure)
        }
    }

    private companion object {
        val LOG: Logger = Logger.getLogger(CitadelLifecycleNotifications::class.java.name)
    }
}
