package ru.fornax.fornaxcitadel.core.storage

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.ComponentType
import ru.fornax.fornaxcitadel.core.component.OpaqueComponent
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.*

class ChunkCord(world: UUID, x: Int, z: Int) : WXZCord(world, x, z) {
    companion object {
        internal const val MIN_Y = -64
        internal const val WORLD_HEIGHT = 384
        internal const val CHUNK_SIZE = 16
        internal const val POSITION_COUNT = CHUNK_SIZE * CHUNK_SIZE * WORLD_HEIGHT
        internal const val MAX_PACKED_POSITION = POSITION_COUNT - 1

        private const val LOCAL_COORDINATE_MASK = CHUNK_SIZE - 1
        private const val Z_SHIFT = 4
        private const val Y_SHIFT = 8

        internal fun packPosition(
            locX: Int,
            locY: Int,
            locZ: Int
        ): Int {
            require(locX in 0 until CHUNK_SIZE) { "Invalid local x: $locX" }
            require(locZ in 0 until CHUNK_SIZE) { "Invalid local z: $locZ" }
            require(locY in MIN_Y until (MIN_Y + WORLD_HEIGHT)) {
                "Invalid y: $locY"
            }

            return ((locY - MIN_Y) shl Y_SHIFT) or
                (locZ shl Z_SHIFT) or
                locX
        }

        internal fun unpackX(packedPosition: Int): Int {
            requireValidPackedPosition(packedPosition)
            return packedPosition and LOCAL_COORDINATE_MASK
        }

        internal fun unpackY(packedPosition: Int): Int {
            requireValidPackedPosition(packedPosition)
            return (packedPosition ushr Y_SHIFT) + MIN_Y
        }

        internal fun unpackZ(packedPosition: Int): Int {
            requireValidPackedPosition(packedPosition)
            return (packedPosition ushr Z_SHIFT) and LOCAL_COORDINATE_MASK
        }

        private fun requireValidPackedPosition(packedPosition: Int) {
            require(packedPosition in 0..MAX_PACKED_POSITION) {
                "Invalid packed position: $packedPosition"
            }
        }
    }

    private var minecraftChunkLoaded = false

    private val ownerToken = Any()
    private val citadels = CitadelsData()
    private val components = ComponentsData()
    private val opaqueComponents = OpaqueComponentsData()

    val size: Int
        get() = citadels.size

    val isEmpty: Boolean
        get() = size == 0

    fun getCitadel(
        locX: Int,
        locY: Int,
        locZ: Int
    ): CitadelEntity? {
        return citadels.getCitadel(locX, locY, locZ)
    }

    fun putCitadel(
        locX: Int,
        locY: Int,
        locZ: Int
    ): CitadelEntity {
        val packedPosition = packPosition(locX, locY, locZ)
        check(citadels.getCitadel(locX, locY, locZ) == null) {
            "Citadel position is already occupied: [$locX, $locY, $locZ]"
        }

        val entity = CitadelEntity(ownerToken, packedPosition)
        citadels.putCitadel(locX, locY, locZ, entity)
        return entity
    }

    fun removeCitadel(
        locX: Int,
        locY: Int,
        locZ: Int
    ): CitadelEntity? {
        val packedPosition = packPosition(locX, locY, locZ)
        val removed = citadels.removeCitadel(locX, locY, locZ) ?: return null
        removeAllComponentsAt(removed, packedPosition)
        return removed
    }

    fun forEachCitadel(
        action: (
            x: Int,
            y: Int,
            z: Int,
            entity: CitadelEntity
        ) -> Unit
    ) {
        citadels.forEachCitadel(action)
    }

    fun <T : Component> getComponent(
        entity: CitadelEntity,
        type: ComponentType<T>
    ): T? {
        val packedPosition = requireCurrentPosition(entity)
        return components.getComponent(packedPosition, type)
    }

    fun <T : Component> putComponent(
        entity: CitadelEntity,
        type: ComponentType<T>,
        component: T
    ): T? {
        val packedPosition = requireCurrentPosition(entity)
        val previous = components.putComponent(
            packedPosition,
            entity,
            type,
            component
        )
        opaqueComponents.remove(packedPosition, type.key)
        entity.addComponent(type.flag)
        return previous
    }

    fun <T : Component> removeComponent(
        entity: CitadelEntity,
        type: ComponentType<T>
    ): T? {
        val packedPosition = requireCurrentPosition(entity)
        val removed = components.removeComponent(packedPosition, type)
        opaqueComponents.remove(packedPosition, type.key)
        if (removed != null) {
            entity.removeComponent(type.flag)
        }
        return removed
    }

    fun removeAllComponents(entity: CitadelEntity) {
        val packedPosition = requireCurrentPosition(entity)
        removeAllComponentsAt(entity, packedPosition)
    }

    fun forEachComponent(
        entity: CitadelEntity,
        action: (ComponentType<*>, Component) -> Unit
    ) {
        val packedPosition = requireCurrentPosition(entity)
        components.forEachComponent(packedPosition, action)
    }

    fun <T : Component> forEachComponentOfType(
        type: ComponentType<T>,
        action: (CitadelEntity, T) -> Unit
    ) {
        components.forEachOfType(type, action)
    }

    internal fun putOpaqueComponent(
        entity: CitadelEntity,
        component: OpaqueComponent
    ): OpaqueComponent? {
        val packedPosition = requireCurrentPosition(entity)
        return opaqueComponents.put(packedPosition, component)
    }

    internal fun forEachOpaqueComponent(
        entity: CitadelEntity,
        action: (OpaqueComponent) -> Unit
    ) {
        val packedPosition = requireCurrentPosition(entity)
        opaqueComponents.forEach(packedPosition, action)
    }

    fun clear() {
        citadels.forEachCitadel { _, _, _, entity ->
            entity.setComponents(0L)
        }
        opaqueComponents.clear()
        components.clear()
        citadels.clear()
    }

    private fun requireCurrentPosition(entity: CitadelEntity): Int {
        require(entity.isOwnedBy(ownerToken)) {
            "Citadel ${entity.id} belongs to another chunk"
        }

        val packedPosition = entity.packedPosition
        check(citadels.getPacked(packedPosition) === entity) {
            "Citadel ${entity.id} is no longer present in this chunk"
        }
        return packedPosition
    }

    private fun removeAllComponentsAt(
        entity: CitadelEntity,
        packedPosition: Int
    ) {
        components.removeAllComponents(packedPosition)
        opaqueComponents.removeAll(packedPosition)
        entity.setComponents(0L)
    }

    /** Tracks the Minecraft chunk lifecycle independently of storage residency and citadel contents. */
    fun isChunkLoaded(): Boolean {
        return minecraftChunkLoaded
    }

    fun minecraftChunkLoaded() {
        minecraftChunkLoaded = true
    }

    fun minecraftChunkUnloaded() {
        minecraftChunkLoaded = false
    }

    private class CitadelsData {

        private val data: Array<Array<Array<Array<CitadelEntity?>?>?>?> =
            arrayOfNulls(GROUP_COUNT)

        var size: Int = 0
            private set

        fun getCitadel(
            locX: Int,
            locY: Int,
            locZ: Int
        ): CitadelEntity? {
            validatePosition(locX, locY, locZ)

            return data[yGroup(locY)]
                ?.get(yOffset(locY))
                ?.get(locX)
                ?.get(locZ)
        }

        fun getPacked(packedPosition: Int): CitadelEntity? {
            return getCitadel(
                unpackX(packedPosition),
                unpackY(packedPosition),
                unpackZ(packedPosition)
            )
        }

        fun putCitadel(
            locX: Int,
            locY: Int,
            locZ: Int,
            entity: CitadelEntity
        ) {
            validatePosition(locX, locY, locZ)

            val group = data[yGroup(locY)]
                ?: arrayOfNulls<Array<Array<CitadelEntity?>?>>(GROUP_HEIGHT).also {
                    data[yGroup(locY)] = it
                }

            val plane = group[yOffset(locY)]
                ?: arrayOfNulls<Array<CitadelEntity?>>(CHUNK_SIZE).also {
                    group[yOffset(locY)] = it
                }

            val row = plane[locX]
                ?: arrayOfNulls<CitadelEntity>(CHUNK_SIZE).also {
                    plane[locX] = it
                }

            check(row[locZ] == null) {
                "Citadel position is already occupied: [$locX, $locY, $locZ]"
            }
            row[locZ] = entity
            size++
        }

        fun removeCitadel(
            locX: Int,
            locY: Int,
            locZ: Int
        ): CitadelEntity? {
            validatePosition(locX, locY, locZ)

            val groupIndex = yGroup(locY)
            val offset = yOffset(locY)
            val group = data[groupIndex] ?: return null
            val plane = group[offset] ?: return null
            val row = plane[locX] ?: return null
            val removed = row[locZ] ?: return null

            row[locZ] = null
            size--

            if (row.all { it == null }) {
                plane[locX] = null
            }
            if (plane.all { it == null }) {
                group[offset] = null
            }
            if (group.all { it == null }) {
                data[groupIndex] = null
            }

            return removed
        }

        fun forEachCitadel(
            action: (
                x: Int,
                y: Int,
                z: Int,
                entity: CitadelEntity
            ) -> Unit
        ) {
            for (groupIndex in data.indices) {
                val group = data[groupIndex] ?: continue

                for (offset in group.indices) {
                    val plane = group[offset] ?: continue
                    val y = MIN_Y + groupIndex * GROUP_HEIGHT + offset

                    for (x in plane.indices) {
                        val row = plane[x] ?: continue

                        for (z in row.indices) {
                            val entity = row[z] ?: continue
                            action(x, y, z, entity)
                        }
                    }
                }
            }
        }

        fun clear() {
            data.fill(null)
            size = 0
        }

        private fun validatePosition(x: Int, y: Int, z: Int) {
            packPosition(x, y, z)
        }

        private companion object {
            const val GROUP_HEIGHT = 24
            const val GROUP_COUNT = WORLD_HEIGHT / GROUP_HEIGHT

            fun yGroup(y: Int): Int = (y - MIN_Y) / GROUP_HEIGHT

            fun yOffset(y: Int): Int = (y - MIN_Y) % GROUP_HEIGHT
        }
    }

    private class ComponentsData {

        private class ComponentsStore<T : Component>(
            val type: ComponentType<T>
        ) {
            private val owners = ArrayList<CitadelEntity>()
            private val components = ArrayList<T>()
            private val indices = Int2IntOpenHashMap().apply {
                defaultReturnValue(MISSING_INDEX)
            }

            val isEmpty: Boolean
                get() = components.isEmpty()

            private fun indexOf(packedPosition: Int): Int {
                return indices.get(packedPosition)
            }

            fun get(packedPosition: Int): T? {
                val index = indexOf(packedPosition)
                if (index == MISSING_INDEX) return null

                return components[index]
            }

            fun put(
                packedPosition: Int,
                entity: CitadelEntity,
                component: T
            ): T? {
                val index = indexOf(packedPosition)

                if (index != MISSING_INDEX) {
                    return components.set(index, component)
                }

                indices.put(packedPosition, components.size)
                owners.add(entity)
                components.add(component)

                return null
            }

            fun remove(packedPosition: Int): T? {
                val index = indexOf(packedPosition)
                if (index == MISSING_INDEX) return null

                val last = components.lastIndex

                val removed = components[index]
                indices.remove(packedPosition)

                // Swap-remove
                if (index != last) {
                    val movedEntity = owners[last]

                    owners[index] = movedEntity
                    components[index] = components[last]
                    indices.put(movedEntity.packedPosition, index)
                }

                owners.removeAt(last)
                components.removeAt(last)

                return removed
            }

            private companion object {
                const val MISSING_INDEX = -1
            }

            fun forEach(
                action: (CitadelEntity, T) -> Unit
            ) {
                for (i in components.indices) {
                    action(owners[i], components[i])
                }
            }
        }

        private val componentsType: Array<ComponentsStore<*>?> =
            arrayOfNulls(TypeManager.MAX_COMPONENT_COUNT)

        @Suppress("UNCHECKED_CAST")
        private fun <T : Component> getStore(
            type: ComponentType<T>
        ): ComponentsStore<T>? {
            require(type.id.toInt() in componentsType.indices)

            val store = componentsType[type.id.toInt()] ?: return null

            check(store.type === type) {
                "Component type index ${type.id.toInt()} is already occupied"
            }

            return store as ComponentsStore<T>
        }

        private fun <T : Component> getOrCreateStore(
            type: ComponentType<T>
        ): ComponentsStore<T> {
            return getStore(type)
                ?: ComponentsStore(type).also {
                    componentsType[type.id.toInt()] = it
                }
        }

        fun <T : Component> getComponent(
            packedPosition: Int,
            type: ComponentType<T>
        ): T? {
            return getStore(type)?.get(packedPosition)
        }

        fun <T : Component> putComponent(
            packedPosition: Int,
            entity: CitadelEntity,
            type: ComponentType<T>,
            component: T
        ): T? {
            require(type.componentClass.isInstance(component)) {
                "Component ${component.javaClass.name} does not match type ${type.componentClass.name}"
            }

            return getOrCreateStore(type).put(
                packedPosition,
                entity,
                component
            )
        }

        fun <T : Component> removeComponent(
            packedPosition: Int,
            type: ComponentType<T>
        ): T? {
            val store = getStore(type) ?: return null
            val removed = store.remove(packedPosition)

            if (store.isEmpty) {
                componentsType[type.id.toInt()] = null
            }

            return removed
        }

        fun removeAllComponents(packedPosition: Int) {
            for (i in componentsType.indices) {
                val store = componentsType[i] ?: continue

                store.remove(packedPosition)

                if (store.isEmpty) {
                    componentsType[i] = null
                }
            }
        }

        fun forEachComponent(
            packedPosition: Int,
            action: (ComponentType<*>, Component) -> Unit
        ) {
            for (store in componentsType) {
                if (store == null) continue

                val component = store.get(packedPosition) ?: continue
                action(store.type, component)
            }
        }

        fun <T : Component> forEachOfType(
            type: ComponentType<T>,
            action: (CitadelEntity, T) -> Unit
        ) {
            getStore(type)?.forEach(action)
        }

        fun clear() {
            componentsType.fill(null)
        }
    }

    private class OpaqueComponentsData {
        private val data = HashMap<Int, LinkedHashMap<Key, OpaqueComponent>>()

        fun put(
            packedPosition: Int,
            component: OpaqueComponent
        ): OpaqueComponent? {
            return data
                .getOrPut(packedPosition, ::LinkedHashMap)
                .put(component.key, component)
        }

        fun remove(
            packedPosition: Int,
            key: Key
        ): OpaqueComponent? {
            val components = data[packedPosition] ?: return null
            val removed = components.remove(key)
            if (components.isEmpty()) {
                data.remove(packedPosition)
            }
            return removed
        }

        fun removeAll(packedPosition: Int) {
            data.remove(packedPosition)
        }

        fun forEach(
            packedPosition: Int,
            action: (OpaqueComponent) -> Unit
        ) {
            data[packedPosition]?.values?.forEach(action)
        }

        fun clear() {
            data.clear()
        }
    }

}
