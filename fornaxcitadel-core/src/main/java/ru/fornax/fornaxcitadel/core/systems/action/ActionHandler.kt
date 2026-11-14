package ru.fornax.fornaxcitadel.core.systems.action

/**
 * Receives an action notification from [HandlerCoordinator], carrying the original intent.
 * ActionManager notifies only after processor SUCCESS; calling the coordinator directly is a manual
 * notification without execution or result validation. The action contains no resolved target snapshot.
 * Runs on the owning game thread. Handlers may react to notifications and read citadel state.
 * Modifying citadels from a handler has undefined behavior and is not checked at runtime;
 * post another action to request changes. Nested action execution is rejected until handling finishes.
 * Direct service calls do not notify handlers.
 */
interface ActionHandler<T : ActionData> {
    val actionClass: Class<T>

    fun handle(action: T)
}
