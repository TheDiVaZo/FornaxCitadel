package ru.fornax.fornaxcitadel.core.systems.action

interface ActionValidator<T : ActionData> {
    val actionClass: Class<T>

    fun validate(action: T): Result

    sealed interface Result {
        /** The action passed this validator's checks. */
        data object Success : Result

        /** This validator found no applicable conditions to check. */
        data object Neutral : Result

        /** This validator rejected the action. */
        data object Rejected : Result
    }
}
