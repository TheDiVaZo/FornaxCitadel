package ru.fornax.fornaxcitadel.core.systems.actio

fun interface Executor<S, A : Action> {
    fun execute(source: S, action: A): Result

    sealed interface Result {
        object OK : Result
        enum class FAILED : Result {
            REJECTED,
            ERROR
        }
    }
}
