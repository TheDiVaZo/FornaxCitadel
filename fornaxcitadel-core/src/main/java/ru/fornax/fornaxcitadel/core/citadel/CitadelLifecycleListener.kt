package ru.fornax.fornaxcitadel.core.citadel

/**
 * Observes completed changes. Runtime IDs expire on reload; removal exposes no live components.
 * Reads, including lazy health refresh, are allowed. Post actions for structural changes or damage.
 */
interface CitadelLifecycleListener {
    fun onPlaced(position: CitadelPosition, runtimeId: Int) {}
    fun onRemoved(position: CitadelPosition, runtimeId: Int, cause: RemovalCause) {}
}
