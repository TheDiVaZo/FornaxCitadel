package ru.fornax.fornaxcitadel.core.serialization

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.ComponentType
import ru.fornax.fornaxcitadel.core.component.OpaqueComponent
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.CitadelEntity
import ru.fornax.fornaxcitadel.core.storage.CitadelSnapshot
import ru.fornax.fornaxcitadel.core.storage.ComponentSnapshot
import ru.fornax.fornaxcitadel.core.storage.OpaqueComponentCandidate
import ru.fornax.fornaxcitadel.core.storage.OpaqueUpdateResult
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.IOException
import java.util.Collections

data class CorruptedComponentPayload(
    val location: WXZCord,
    val packedPosition: Int,
    val key: Key,
    val cause: Throwable
)

internal data class OpaqueComponentResolution(
    val candidate: OpaqueComponentCandidate,
    val result: ComponentDecodeResult
)

class ChunkCordSnapshotMapper(
    private val typeManager: TypeManager,
    private val corruptedComponentHandler: (CorruptedComponentPayload) -> Unit = {}
) {
    /** Omits components whose write fails; the live chunk is left unchanged. */
    fun snapshot(chunk: ChunkCord): ChunkCordSnapshot {
        val citadels = ArrayList<CitadelSnapshot>(chunk.size)
        chunk.forEachCitadel { _, _, _, entity ->
            val componentKeys = HashSet<Key>()
            val components = ArrayList<ComponentSnapshot>()

            chunk.forEachComponent(entity) { type, component ->
                check(componentKeys.add(type.key)) {
                    "Duplicate component key ${type.key} for citadel ${entity.id}"
                }
                val snapshot = try {
                    ComponentSerializer.encode(type, component)
                } catch (_: Exception) {
                    // Discard the failed component, including any partially written payload.
                    return@forEachComponent
                }
                components.add(snapshot)
            }
            chunk.forEachOpaqueComponent(entity) { component ->
                check(componentKeys.add(component.key)) {
                    "Duplicate component key ${component.key} for citadel ${entity.id}"
                }
                components.add(ComponentSerializer.encode(component))
            }

            citadels.add(CitadelSnapshot(entity.packedPosition, components))
        }

        return ChunkCordSnapshot(chunk.location, citadels)
    }

    @Throws(IOException::class)
    fun restore(snapshot: ChunkCordSnapshot): ChunkCord {
        val chunk = ChunkCord(snapshot.location)
        var discardedCorruptedComponent = false
        val registeredTypes = typeManager.getTypes(
            snapshot.citadels
                .flatMapTo(LinkedHashSet()) { citadel ->
                    citadel.components.map(ComponentSnapshot::key)
                }
        )
        for (citadel in snapshot.citadels) {
            val entity = chunk.putCitadel(
                ChunkCord.unpackX(citadel.packedPosition),
                ChunkCord.unpackY(citadel.packedPosition),
                ChunkCord.unpackZ(citadel.packedPosition)
            )

            for (component in citadel.components) {
                val type = registeredTypes[component.key]
                when (val result = ComponentSerializer.decodeSafely(component, type)) {
                    is ComponentDecodeResult.Decoded -> {
                        putDecodedComponent(chunk, entity, result)
                    }
                    ComponentDecodeResult.Unknown -> {
                        chunk.putOpaqueComponent(
                            entity,
                            OpaqueComponent(component.key, component.payloadData)
                        )
                    }
                    is ComponentDecodeResult.Corrupted -> {
                        discardedCorruptedComponent = true
                        reportCorrupted(chunk, citadel.packedPosition, component.key, result.cause)
                    }
                    is ComponentDecodeResult.InfrastructureFailure -> {
                        throw IOException(
                            "Cannot restore component ${component.key} " +
                                "for citadel ${citadel.packedPosition}",
                            result.cause
                        )
                    }
                }
            }
        }
        // Loading existing data is not a change; removing corrupted payloads is.
        if (!discardedCorruptedComponent) chunk.resetDirty()
        return chunk
    }

    internal fun captureOpaqueComponents(
        chunk: ChunkCord,
        key: Key? = null
    ): List<OpaqueComponentCandidate> {
        return chunk.captureOpaqueComponents(key)
    }

    internal fun decodeOpaqueComponents(
        candidates: List<OpaqueComponentCandidate>
    ): List<OpaqueComponentResolution> {
        val resolutions = ArrayList<OpaqueComponentResolution>(candidates.size)
        val registeredTypes = typeManager.getTypes(
            candidates.mapTo(LinkedHashSet(), OpaqueComponentCandidate::key)
        )
        for (candidate in candidates) {
            val component = candidate.toSnapshot()
            val result = ComponentSerializer.decodeSafely(
                component,
                registeredTypes[candidate.key]
            )
            resolutions.add(OpaqueComponentResolution(candidate, result))
        }
        return Collections.unmodifiableList(resolutions)
    }

    @Throws(IOException::class)
    internal fun applyOpaqueComponents(
        chunk: ChunkCord,
        resolutions: List<OpaqueComponentResolution>
    ): OpaqueUpdateResult {
        for ((candidate, result) in resolutions) {
            if (result is ComponentDecodeResult.InfrastructureFailure) {
                throw IOException(
                    "Cannot restore opaque component ${candidate.key} " +
                        "for citadel ${candidate.packedPosition}",
                    result.cause
                )
            }
        }

        var restored = 0
        var discarded = 0
        var unresolved = 0
        var skipped = 0

        for ((candidate, result) in resolutions) {
            when (result) {
                is ComponentDecodeResult.Decoded -> {
                    if (chunk.replaceOpaqueComponent(
                            candidate,
                            result.type,
                            result.component
                        )
                    ) {
                        restored++
                    } else {
                        skipped++
                    }
                }
                ComponentDecodeResult.Unknown -> {
                    if (chunk.isOpaqueComponentCurrent(candidate)) {
                        unresolved++
                    } else {
                        skipped++
                    }
                }
                is ComponentDecodeResult.Corrupted -> {
                    if (chunk.discardOpaqueComponent(candidate)) {
                        discarded++
                        reportCorrupted(
                            chunk,
                            candidate.packedPosition,
                            candidate.key,
                            result.cause
                        )
                    } else {
                        skipped++
                    }
                }
                is ComponentDecodeResult.InfrastructureFailure -> {
                    error("Infrastructure failures are handled before applying resolutions")
                }
            }
        }

        return OpaqueUpdateResult(restored, discarded, unresolved, skipped)
    }

    @Suppress("UNCHECKED_CAST")
    private fun putDecodedComponent(
        chunk: ChunkCord,
        entity: CitadelEntity,
        decoded: ComponentDecodeResult.Decoded
    ) {
        val typedType = decoded.type as ComponentType<Component<*>>
        chunk.putComponent(
            entity,
            typedType,
            decoded.component
        )
    }

    private fun reportCorrupted(
        chunk: ChunkCord,
        packedPosition: Int,
        key: Key,
        cause: Throwable
    ) {
        try {
            corruptedComponentHandler(
                CorruptedComponentPayload(
                    chunk.location,
                    packedPosition,
                    key,
                    cause
                )
            )
        } catch (_: Exception) {
        }
    }
}
