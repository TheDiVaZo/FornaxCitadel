package ru.fornax.fornaxcitadel.core.systems.observation

/**
 * An observation algorithm together with its implementation-specific settings and dependencies.
 * Implementations decide when to search and publish complete sets of loaded citadels.
 * One implementation may share listeners, indexes and timers between multiple subscriptions.
 * All calls, including publications and closing subscriptions, belong to the owning game thread.
 */
fun interface Observation<V> {
    /**
     * Starts observing [viewer]. Publish an initial set, synchronously or when it becomes available.
     * The service buffers synchronous publications until this method returns successfully.
     * If startup throws, the implementation must release its partially acquired resources itself.
     * The returned subscription releases only this observation; closing it must be idempotent.
     */
    fun observe(viewer: V, publisher: ObservationPublisher): ObservationSubscription
}
