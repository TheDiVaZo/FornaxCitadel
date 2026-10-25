package ru.fornax.fornaxcitadel.core.storage

import java.util.concurrent.CompletableFuture
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
