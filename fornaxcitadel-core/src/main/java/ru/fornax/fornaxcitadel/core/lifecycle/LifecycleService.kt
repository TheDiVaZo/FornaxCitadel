package ru.fornax.fornaxcitadel.core.lifecycle

/**
 * A reusable service lifecycle on the owning game thread.
 */
interface LifecycleService {
    /** Repeated calls while enabled do not duplicate registrations. */
    fun enable()

    /** Stops active work and releases resources acquired by [enable]. */
    fun disable()
}
