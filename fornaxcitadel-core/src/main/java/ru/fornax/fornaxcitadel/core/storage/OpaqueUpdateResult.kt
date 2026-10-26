package ru.fornax.fornaxcitadel.core.storage

import java.util.Collections

data class OpaqueUpdateResult(
    val restored: Int = 0,
    val discarded: Int = 0,
    val unresolved: Int = 0,
    val skipped: Int = 0
) {
    init {
        require(restored >= 0) { "restored must not be negative" }
        require(discarded >= 0) { "discarded must not be negative" }
        require(unresolved >= 0) { "unresolved must not be negative" }
        require(skipped >= 0) { "skipped must not be negative" }
    }

    operator fun plus(other: OpaqueUpdateResult): OpaqueUpdateResult {
        return OpaqueUpdateResult(
            restored = restored + other.restored,
            discarded = discarded + other.discarded,
            unresolved = unresolved + other.unresolved,
            skipped = skipped + other.skipped
        )
    }
}

class OpaqueBatchUpdateResult(
    /** Chunks with matching candidates, plus chunks whose scan or update failed. */
    val chunksFound: Int,
    val chunksProcessed: Int,
    val chunksFailed: Int,
    val components: OpaqueUpdateResult,
    failures: Map<WXZCord, Throwable> = emptyMap()
) {
    val failures: Map<WXZCord, Throwable> =
        Collections.unmodifiableMap(LinkedHashMap(failures))

    init {
        require(chunksFound >= 0) { "chunksFound must not be negative" }
        require(chunksProcessed >= 0) { "chunksProcessed must not be negative" }
        require(chunksFailed >= 0) { "chunksFailed must not be negative" }
        require(chunksProcessed + chunksFailed <= chunksFound) {
            "Processed and failed chunks exceed found chunks"
        }
    }
}
