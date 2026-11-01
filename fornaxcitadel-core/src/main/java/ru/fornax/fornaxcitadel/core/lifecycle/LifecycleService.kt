package ru.fornax.fornaxcitadel.core.lifecycle

/**
 * A reusable service lifecycle on the owning game thread.
 * Construction supplies dependencies and registers persistent component types.
 * Enabling and disabling only attach and detach the service's runtime registrations.
 */
interface LifecycleService {
    /** Repeated calls while enabled do not duplicate registrations. */
    fun enable()

    /** Releases owned registrations and transient work; persistent data and settings remain. */
    fun disable()
}
