package ru.fornax.fornaxcitadel.core.systems.actio

/** Synchronous dispatcher for a single kind of source. Use on the owning game thread. */
class Dispatcher<S>(
    val eventPublisher: ActionEventPublisher<S>,
    private val prerequisite: (S, Action) -> Boolean
) {
    constructor(eventPublisher: ActionEventPublisher<S>) : this(eventPublisher, { _, _ -> true })

    private val authorizerCoordinator = ActionAuthorizerCoordinator<S>()
    private val executors = mutableMapOf<Class<out Action>, ExecutorValue<out Action>>()
    private var checking = false

    private inner class ExecutorValue<A : Action>(
        private val actionClass: Class<A>,
        private val executor: Executor<S, A>
    ) {
        fun execute(source: S, action: Action): Executor.Result {
            val castedAction = actionClass.cast(action)
            return executor.execute(source, castedAction)
        }
    }

    fun <A : Action> registerExecutor(
        clazz: Class<A>,
        executor: Executor<S, A>
    ) {
        require(clazz !in executors) { "An executor is already registered for ${clazz.name}" }
        executors[clazz] = ExecutorValue(clazz, executor)
    }

    fun unregister(clazz: Class<out Action>) {
        executors.remove(clazz)
    }

    /** Multiple authorizers can grant access to an action. Registering the same instance is a no-op. */
    fun registerAuthorizer(actionAuthorizer: ActionAuthorizer<S, *>) = authorizerCoordinator.register(actionAuthorizer)

    /** Removes this instance, accounting for registration changes already queued by callbacks. */
    fun unregisterAuthorizer(actionAuthorizer: ActionAuthorizer<S, *>): Boolean = authorizerCoordinator.unregister(actionAuthorizer)

    /**
     * Executors are selected by exact action class. Mandatory source and target prerequisites are checked
     * before authorization and again after event listeners have had an opportunity to change the world.
     * Only an executor may dispatch another request on this dispatcher. Callback exceptions propagate.
     */
    fun dispatch(source: S, action: Action): DispatchResult {
        check(!checking) { "Cannot dispatch another action while checking a request" }
        val executor = executors[action.javaClass] ?: return DispatchResult.Failed.NO_FOUND_EXECUTOR
        checking = true
        try {
            if (!prerequisite(source, action) || !authorizerCoordinator.testAuthorize(source, action)) {
                return DispatchResult.Failed.NOT_AUTHORIZED
            }
            when (eventPublisher.publish(source, action)) {
                ActionEventPublisher.Result.CANCELLED -> return DispatchResult.Failed.CANCELED_BY_EVENT
                ActionEventPublisher.Result.INVALIDATED -> return DispatchResult.Failed.EXECUTION_REJECTED
                ActionEventPublisher.Result.OK -> Unit
            }
            if (!prerequisite(source, action)) return DispatchResult.Failed.NOT_AUTHORIZED
        } finally {
            checking = false
        }

        return when (executor.execute(source, action)) {
            Executor.Result.OK -> DispatchResult.Ok
            Executor.Result.FAILED.ERROR -> DispatchResult.Failed.EXECUTION_ERROR
            Executor.Result.FAILED.REJECTED -> DispatchResult.Failed.EXECUTION_REJECTED
        }
    }

    sealed interface DispatchResult {
        val isSuccess: Boolean
            get() = this is Ok
        val isFailure: Boolean
            get() = this is Failed

        data object Ok : DispatchResult
        enum class Failed : DispatchResult {
            NO_FOUND_EXECUTOR,
            NOT_AUTHORIZED,
            CANCELED_BY_EVENT,
            EXECUTION_REJECTED,
            EXECUTION_ERROR
        }
    }
}
