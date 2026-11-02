package ru.fornax.fornaxcitadel.core.systems.observation

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle

/** A publication endpoint scoped to one viewer registration, not to a reusable viewer UUID. */
fun interface ObservationPublisher {
    /**
     * Replaces this registration's entire observed set. An empty set explicitly clears it.
     * The service copies the input before applying it, deduplicates entities, and ignores stale
     * handles and handles owned by another component manager. An iteration failure leaves the
     * previous set unchanged. Publications after closing the registration are ignored.
     * Publications from system callbacks are applied after the current dispatch finishes.
     */
    fun publish(citadels: Iterable<CitadelHandle>)
}
