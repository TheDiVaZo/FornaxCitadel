package ru.fornax.fornaxcitadel.core.systems.action

/**
 * Receives an action notification carrying the original intent.
 * ActionManager notifies handlers only after the processor or entire batch commit succeeds.
 * The action contains no resolved target snapshot.
 * Runs on the owning game thread. Handlers may react to notifications and read citadel state.
 * Modifying citadels from a handler has undefined behavior and is not checked at runtime;
 * post another action to request changes. Nested action execution is rejected until handling finishes.
 * Direct service calls do not notify handlers.
 */
interface ActionHandler<T : Action> {
    val actionClass: Class<T>

    fun handle(action: T)
}
