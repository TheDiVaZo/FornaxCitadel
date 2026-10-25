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
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.IOException

class ChunkCordSnapshotMapper(
    private val typeManager: TypeManager
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

        return ChunkCordSnapshot(chunk.world, chunk.x, chunk.z, citadels)
    }

    @Throws(IOException::class)
    fun restore(snapshot: ChunkCordSnapshot): ChunkCord {
        val chunk = ChunkCord(snapshot.world, snapshot.x, snapshot.z)
        for (citadel in snapshot.citadels) {
            val entity = chunk.putCitadel(
                ChunkCord.unpackX(citadel.packedPosition),
                ChunkCord.unpackY(citadel.packedPosition),
                ChunkCord.unpackZ(citadel.packedPosition)
            )

            for (component in citadel.components) {
                val type = typeManager.getType(component.key)
                if (type == null) {
                    chunk.putOpaqueComponent(
                        entity,
                        OpaqueComponent(component.key, component.toByteArray())
                    )
                } else {
                    decodeComponent(chunk, entity, type, component)
                }
            }
        }
        return chunk
    }

    @Suppress("UNCHECKED_CAST")
    @Throws(IOException::class)
    private fun decodeComponent(
        chunk: ChunkCord,
        entity: CitadelEntity,
        type: ComponentType<out Component>,
        component: ComponentSnapshot
    ) {
        val typedType = type as ComponentType<Component>
        chunk.putComponent(
            entity,
            typedType,
            ComponentSerializer.decode(component, typedType)
        )
    }
}
