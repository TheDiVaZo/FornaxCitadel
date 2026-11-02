package ru.fornax.fornaxcitadel.core.systems.observation

/** An owner-thread subscription. Repeated closing must have no additional effects. */
fun interface ObservationSubscription : AutoCloseable {
    override fun close()
}
