package ru.fornax.fornaxcitadel.core.systems.action

/**
 * Checks an action on the owning game thread without changing gameplay state, permissions or
 * its resolved target. Authorization runs once before processing, including for posted actions.
 * A validator that only enforces a constraint should return [Result.Neutral] when it passes.
 */
interface ActionValidator<T : ActionData> {
    val actionClass: Class<T>

    fun validate(action: T): Result

    sealed interface Result {
        /** Explicitly authorizes the action unless another validator rejects it. */
        data object Success : Result

        /** Abstains from authorization, including when a constraint passed or no rule applies. */
        data object Neutral : Result

        /** Rejects the action regardless of any other validator's result or action metadata. */
        data object Rejected : Result
    }
}
