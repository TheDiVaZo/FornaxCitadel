package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key

/**
 * Describes an action's origin. Implementations must hold immutable snapshots because actions may be queued.
 * The key identifies the source for attribution; it does not grant permission.
 */
interface ActionSource {
    val key: Key
}
