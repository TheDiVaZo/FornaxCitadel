package ru.fornax.fornaxcitadel.core.storage

import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture

internal class ChunkSlot(val location: WXZCord) {
    var chunk: ChunkCord? = null
    var pendingUnloadSave: PendingChunkSave? = null
    internal val operations = ArrayDeque<ChunkOperation<*>>()
    internal var running = false

    fun hasAccepted(kind: ChunkOperationKind): Boolean {
        return operations.any { it.kind == kind }
    }
}

internal class PendingChunkSave(
    val snapshot: ChunkCordSnapshot,
    private val dirtyState: ChunkDirtyState,
    private val revision: Long
) {
    fun markSaved() {
        dirtyState.resetDirty(revision)
    }
}

internal enum class ChunkOperationKind {
    LOAD,
    UNLOAD,
    OTHER
}

internal class ChunkOperation<T>(
    val kind: ChunkOperationKind,
    private val action: (ChunkSlot) -> CompletableFuture<T>
) {
    val result = CompletableFuture<T>()
    internal lateinit var slot: ChunkSlot

    internal fun execute(): CompletableFuture<*> {
        return action(slot)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun complete(value: Any?, error: Throwable?) {
        if (error == null) {
            result.complete(value as T)
        } else {
            result.completeExceptionally(error.unwrapCompletionException())
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <R> typedResult(): CompletableFuture<R> {
        return result as CompletableFuture<R>
    }
}

/** Advances synchronous operation chains iteratively instead of growing the call stack. */
internal class ChunkOperationDispatcher(
    private val stateLock: Any,
    private val onIdle: (ChunkSlot) -> Unit
) {
    private val completionLock = Any()
    private val completions = ArrayDeque<OperationCompletion>()
    private var drainingCompletions = false

    fun enqueueLocked(
        slot: ChunkSlot,
        operation: ChunkOperation<*>
    ): ChunkOperation<*>? {
        operation.slot = slot
        slot.operations.addLast(operation)
        if (slot.running) return null

        slot.running = true
        return operation
    }

    fun start(operation: ChunkOperation<*>) {
        val execution = try {
            operation.execute()
        } catch (exception: Exception) {
            CompletableFuture.failedFuture<Any?>(exception)
        }

        execution.whenComplete { value, error ->
            enqueueCompletion(OperationCompletion(operation, value, error))
        }
    }

    private fun enqueueCompletion(completion: OperationCompletion) {
        val shouldDrain = synchronized(completionLock) {
            completions.addLast(completion)
            if (drainingCompletions) {
                false
            } else {
                drainingCompletions = true
                true
            }
        }
        if (shouldDrain) drainCompletions()
    }

    private fun drainCompletions() {
        val publications = ArrayDeque<OperationCompletion>()
        while (true) {
            val completion = synchronized(completionLock) {
                val next = completions.pollFirst()
                if (next == null) drainingCompletions = false
                next
            } ?: break

            val nextOperation = finish(completion.operation)
            publications.addLast(completion)
            nextOperation?.let(::start)
        }

        while (publications.isNotEmpty()) {
            val completion = publications.removeFirst()
            completion.operation.complete(completion.value, completion.error)
        }
    }

    private fun finish(operation: ChunkOperation<*>): ChunkOperation<*>? {
        return synchronized(stateLock) {
            val slot = operation.slot
            check(slot.operations.peekFirst() === operation)
            slot.operations.removeFirst()

            val next = slot.operations.peekFirst()
            if (next == null) {
                slot.running = false
                onIdle(slot)
            }
            next
        }
    }

    private data class OperationCompletion(
        val operation: ChunkOperation<*>,
        val value: Any?,
        val error: Throwable?
    )
}
