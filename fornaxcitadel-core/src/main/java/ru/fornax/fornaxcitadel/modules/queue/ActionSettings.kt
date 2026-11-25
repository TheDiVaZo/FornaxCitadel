package ru.fornax.fornaxcitadel.modules.queue

data class ActionSettings(
    val maxActionsPerTick: Int = 1024,
    val actionFailureHandler: (Exception) -> Unit = ActionQueueService.DEFAULT_FAILURE_HANDLER
) {
    init {
        require(maxActionsPerTick > 0) { "Action limit per tick must be positive" }
    }
}
