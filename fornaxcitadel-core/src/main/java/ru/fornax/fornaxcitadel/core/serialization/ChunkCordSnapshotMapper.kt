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

internal sealed interface OpaqueComponentResolution {
    val candidate: OpaqueComponentCandidate

    data class Decoded(
        override val candidate: OpaqueComponentCandidate,
        val type: ComponentType<out Component>,
        val component: Component
    ) : OpaqueComponentResolution

    data class Unknown(
        override val candidate: OpaqueComponentCandidate
    ) : OpaqueComponentResolution

    data class Corrupted(
        override val candidate: OpaqueComponentCandidate,
        val cause: Throwable
    ) : OpaqueComponentResolution

    data class InfrastructureFailure(
        override val candidate: OpaqueComponentCandidate,
        val cause: Throwable
    ) : OpaqueComponentResolution
}

class ChunkCordSnapshotMapper(
    private val typeManager: TypeManager,
    private val corruptedComponentHandler: (CorruptedComponentPayload) -> Unit = {}
) {
    fun snapshot(chunk: ChunkCord): ChunkCordSnapshot {
        val citadels = ArrayList<CitadelSnapshot>(chunk.size)
        chunk.forEachCitadel { _, _, _, entity ->
            val componentKeys = HashSet<Key>()
            val components = ArrayList<ComponentSnapshot>()

            chunk.forEachComponent(entity) { type, component ->
                check(componentKeys.add(type.key)) {
                    "Duplicate component key ${type.key} for citadel ${entity.id}"
                }
                components.add(ComponentSerializer.encode(type, component))
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
            resolutions.add(result.toOpaqueResolution(candidate))
        }
        return Collections.unmodifiableList(resolutions)
    }

    @Throws(IOException::class)
    internal fun applyOpaqueComponents(
        chunk: ChunkCord,
        resolutions: List<OpaqueComponentResolution>
    ): OpaqueUpdateResult {
        val infrastructureFailure = resolutions
            .filterIsInstance<OpaqueComponentResolution.InfrastructureFailure>()
            .firstOrNull()
        if (infrastructureFailure != null) {
            throw IOException(
                "Cannot restore opaque component ${infrastructureFailure.candidate.key} " +
                    "for citadel ${infrastructureFailure.candidate.packedPosition}",
                infrastructureFailure.cause
            )
        }

        var restored = 0
        var discarded = 0
        var unresolved = 0
        var skipped = 0

        for (resolution in resolutions) {
            when (resolution) {
                is OpaqueComponentResolution.Decoded -> {
                    if (chunk.replaceOpaqueComponent(
                            resolution.candidate,
                            resolution.type,
                            resolution.component
                        )
                    ) {
                        restored++
                    } else {
                        skipped++
                    }
                }
                is OpaqueComponentResolution.Unknown -> {
                    if (chunk.isOpaqueComponentCurrent(resolution.candidate)) {
                        unresolved++
                    } else {
                        skipped++
                    }
                }
                is OpaqueComponentResolution.Corrupted -> {
                    if (chunk.discardOpaqueComponent(resolution.candidate)) {
                        discarded++
                        reportCorrupted(
                            chunk,
                            resolution.candidate.packedPosition,
                            resolution.candidate.key,
                            resolution.cause
                        )
                    } else {
                        skipped++
                    }
                }
                is OpaqueComponentResolution.InfrastructureFailure -> {
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
        val typedType = decoded.type as ComponentType<Component>
        chunk.putComponent(
            entity,
            typedType,
            decoded.component
        )
    }

    private fun ComponentDecodeResult.toOpaqueResolution(
        candidate: OpaqueComponentCandidate
    ): OpaqueComponentResolution {
        return when (this) {
            is ComponentDecodeResult.Decoded -> {
                OpaqueComponentResolution.Decoded(candidate, type, component)
            }
            ComponentDecodeResult.Unknown -> {
                OpaqueComponentResolution.Unknown(candidate)
            }
            is ComponentDecodeResult.Corrupted -> {
                OpaqueComponentResolution.Corrupted(candidate, cause)
            }
            is ComponentDecodeResult.InfrastructureFailure -> {
                OpaqueComponentResolution.InfrastructureFailure(candidate, cause)
            }
        }
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
