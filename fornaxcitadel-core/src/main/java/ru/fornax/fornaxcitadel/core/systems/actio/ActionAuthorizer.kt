package ru.fornax.fornaxcitadel.core.systems.actio

/** Grants this source permission for matching actions. A false return lets other authorizers decide. */
interface ActionAuthorizer<S, A : Action> {
    val actionClass: Class<A>
    fun allows(source: S, action: A): Boolean
}
