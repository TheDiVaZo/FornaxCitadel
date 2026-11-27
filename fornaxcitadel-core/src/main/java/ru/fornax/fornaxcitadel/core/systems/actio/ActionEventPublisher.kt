package ru.fornax.fornaxcitadel.core.systems.actio

/** Synchronous pre-execution event. It may cancel the request after an authorizer granted it. */
fun interface ActionEventPublisher<S> {
    fun publish(source: S, action: Action): Result

    enum class Result {
        OK,
        CANCELLED,
        /** The target or source no longer matches the request after event delivery. */
        INVALIDATED
    }
}
