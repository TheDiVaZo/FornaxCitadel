package ru.fornax.fornaxcitadel.core.systems.action

data class ActionMeta(
    /** Whether execution is allowed when no validator explicitly authorizes or rejects it. */
    val executeIfNeutralActionResult: Boolean
) {
    companion object {
        @JvmStatic
        val DEFAULT = ActionMeta(true)
    }
}
