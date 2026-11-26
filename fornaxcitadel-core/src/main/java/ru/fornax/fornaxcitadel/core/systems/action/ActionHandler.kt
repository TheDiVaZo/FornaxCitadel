package ru.fornax.fornaxcitadel.core.systems.action

/**
 * Receives an action notification carrying the original intent.
 * After a successful root execution, handlers observe the final state. Successful children
 * are delivered in completion order before their parent. A failed branch emits no notifications.
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
