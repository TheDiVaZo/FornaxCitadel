package ru.fornax.fornaxcitadel.core.storage

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException

internal fun completedVoid(): CompletableFuture<Void> {
    return CompletableFuture.completedFuture(null)
}

internal fun Throwable.unwrapCompletionException(): Throwable {
    var current = this
    while (current is CompletionException && current.cause != null) {
        current = current.cause!!
    }
    return current
}

internal fun <T> CompletableFuture<T>.nonCancellingView(): CompletableFuture<T> {
    return thenApply { it }
}

internal fun combineFutureResults(
    futures: Collection<CompletableFuture<*>>
): CompletableFuture<Void> {
    if (futures.isEmpty()) return completedVoid()

    val results = futures.toList()
    val combined = CompletableFuture<Void>()
    CompletableFuture.allOf(*results.toTypedArray()).whenComplete { _, _ ->
        val failures = results.mapNotNull { future ->
            try {
                future.join()
                null
            } catch (exception: CompletionException) {
                exception.unwrapCompletionException()
            } catch (exception: CancellationException) {
                exception
            }
        }

        when (failures.size) {
            0 -> combined.complete(null)
            1 -> combined.completeExceptionally(failures.first())
            else -> {
                val aggregate = IllegalStateException(
                    "${failures.size} asynchronous operations failed",
                    failures.first()
                )
                failures.drop(1).forEach(aggregate::addSuppressed)
                combined.completeExceptionally(aggregate)
            }
        }
    }
    return combined
}
