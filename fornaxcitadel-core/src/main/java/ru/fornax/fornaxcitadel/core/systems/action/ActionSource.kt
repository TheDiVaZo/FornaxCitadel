package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key

/**
 * Describes an action's origin. Implementations may hold immutable snapshots or live references and
 * must document their lifetime. Validators must check live references when queued actions execute.
 * The key identifies the source for attribution; it does not grant permission.
 */
interface ActionSource {
    val key: Key
}
