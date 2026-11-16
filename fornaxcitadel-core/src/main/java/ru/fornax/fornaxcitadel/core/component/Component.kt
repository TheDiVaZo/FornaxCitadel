package ru.fornax.fornaxcitadel.core.component

import ru.fornax.fornaxcitadel.core.serialization.ReadAndWriteAble

/**
 * Serializable, authoritative state that is directly readable on the owning game thread.
 * Reads must not refresh, mutate, or mark components dirty. An operation that changes state
 * must update its components before returning; readers never need a service call to refresh them.
 * Time-dependent values are derived from stored timestamps and parameters without changing state.
 */
abstract class Component<R : ReadableComponent> : ReadAndWriteAble() {
    /**
     * This same instance exposed through its read-only interface, never a wrapper or a snapshot.
     * The interface must not expose mutations, including deserialization or mutable nested state.
     */
    abstract val readable: R
}
