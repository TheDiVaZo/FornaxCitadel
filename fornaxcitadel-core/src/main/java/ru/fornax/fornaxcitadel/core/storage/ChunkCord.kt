package ru.fornax.fornaxcitadel.core.storage

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.ComponentType
import ru.fornax.fornaxcitadel.core.component.OpaqueComponent
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.LinkedHashMap

/** Must only be applied to the ChunkCord from which it was captured. */
internal class OpaqueComponentCandidate internal constructor(
    val packedPosition: Int,
    val key: Key,
    component: OpaqueComponent
) {
    internal val expectedComponent = component

    fun toSnapshot(): ComponentSnapshot {
        return ComponentSnapshot(key, expectedComponent.payloadData)
    }
}

class ChunkCord(val location: WXZCord) {
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
            y: Int,
            locZ: Int
        ): Int {
            require(locX in 0 until CHUNK_SIZE) { "Invalid local x: $locX" }
            require(locZ in 0 until CHUNK_SIZE) { "Invalid local z: $locZ" }
            require(y in MIN_Y until (MIN_Y + WORLD_HEIGHT)) {
                "Invalid y: $y"
            }

            return ((y - MIN_Y) shl Y_SHIFT) or
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
    internal val dirtyState = ChunkDirtyState()

    private val citadels = CitadelsData()
    private val components = ComponentsData()
    private val opaqueComponents = OpaqueComponentsData()

    val size: Int
        get() = citadels.size

    val isEmpty: Boolean
        get() = size == 0

    val isDirty: Boolean
        get() = dirtyState.isDirty

    /** Call on the owning thread after changing a component's fields directly. */
    fun markDirty() {
        dirtyState.markDirty()
    }

    /** Marks the current contents clean without saving them. */
    fun resetDirty() {
        dirtyState.resetDirty()
    }

    fun getCitadel(
        locX: Int,
        y: Int,
        locZ: Int
    ): CitadelEntity? {
        return citadels.getCitadel(locX, y, locZ)
    }

    fun putCitadel(
        locX: Int,
        y: Int,
        locZ: Int
    ): CitadelEntity {
        val packedPosition = packPosition(locX, y, locZ)
        check(citadels.getCitadel(locX, y, locZ) == null) {
            "Citadel position is already occupied: [$locX, $y, $locZ]"
        }

        val entity = CitadelEntity(packedPosition)
        citadels.putCitadel(locX, y, locZ, entity)
        markDirty()
        return entity
    }

    fun removeCitadel(
        locX: Int,
        y: Int,
        locZ: Int
    ): CitadelEntity? {
        val packedPosition = packPosition(locX, y, locZ)
        val removed = citadels.removeCitadel(locX, y, locZ) ?: return null
        removeAllComponentsAt(removed, packedPosition)
        markDirty()
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
        markDirty()
        return previous
    }

    fun <T : Component> removeComponent(
        entity: CitadelEntity,
        type: ComponentType<T>
    ): T? {
        val packedPosition = requireCurrentPosition(entity)
        val removed = components.removeComponent(packedPosition, type)
        val removedOpaque = opaqueComponents.remove(packedPosition, type.key)
        if (removed != null) {
            entity.removeComponent(type.flag)
        }
        if (removed != null || removedOpaque != null) markDirty()
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
        val previous = opaqueComponents.put(packedPosition, component)
        markDirty()
        return previous
    }

    internal fun forEachOpaqueComponent(
        entity: CitadelEntity,
        action: (OpaqueComponent) -> Unit
    ) {
        val packedPosition = requireCurrentPosition(entity)
        opaqueComponents.forEach(packedPosition, action)
    }

    internal fun captureOpaqueComponents(
        key: Key? = null
    ): List<OpaqueComponentCandidate> {
        return opaqueComponents.capture(key)
    }

    internal fun isOpaqueComponentCurrent(
        candidate: OpaqueComponentCandidate
    ): Boolean {
        return citadels.getPacked(candidate.packedPosition) != null &&
            opaqueComponents.isCurrent(candidate)
    }

    internal fun replaceOpaqueComponent(
        candidate: OpaqueComponentCandidate,
        type: ComponentType<out Component>,
        component: Component
    ): Boolean {
        require(candidate.key == type.key) {
            "Opaque key ${candidate.key} does not match component type ${type.key}"
        }

        val entity = citadels.getPacked(candidate.packedPosition) ?: return false
        if (!opaqueComponents.isCurrent(candidate)) return false

        putUntypedComponent(entity, type, component)
        return true
    }

    internal fun discardOpaqueComponent(
        candidate: OpaqueComponentCandidate
    ): Boolean {
        if (citadels.getPacked(candidate.packedPosition) == null) return false
        if (!opaqueComponents.removeIfCurrent(candidate)) return false
        markDirty()
        return true
    }

    fun clear() {
        if (isEmpty) return
        citadels.forEachCitadel { _, _, _, entity ->
            entity.setComponents(0L)
        }
        opaqueComponents.clear()
        components.clear()
        citadels.clear()
        markDirty()
    }

    private fun requireCurrentPosition(entity: CitadelEntity): Int {
        val packedPosition = entity.packedPosition
        check(citadels.getPacked(packedPosition) === entity) {
            "Citadel ${entity.id} is not present in this chunk"
        }
        return packedPosition
    }

    private fun removeAllComponentsAt(
        entity: CitadelEntity,
        packedPosition: Int
    ) {
        val removed = components.removeAllComponents(packedPosition)
        val removedOpaque = opaqueComponents.removeAll(packedPosition)
        entity.setComponents(0L)
        if (removed || removedOpaque) markDirty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun putUntypedComponent(
        entity: CitadelEntity,
        type: ComponentType<out Component>,
        component: Component
    ) {
        putComponent(
            entity,
            type as ComponentType<Component>,
            component
        )
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
            y: Int,
            locZ: Int
        ): CitadelEntity? {
            validatePosition(locX, y, locZ)

            return data[yGroup(y)]
                ?.get(yOffset(y))
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

        fun removeAllComponents(packedPosition: Int): Boolean {
            var removed = false
            for (i in componentsType.indices) {
                val store = componentsType[i] ?: continue

                if (store.remove(packedPosition) != null) removed = true

                if (store.isEmpty) {
                    componentsType[i] = null
                }
            }
            return removed
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

        fun removeAll(packedPosition: Int): Boolean {
            return data.remove(packedPosition) != null
        }

        fun forEach(
            packedPosition: Int,
            action: (OpaqueComponent) -> Unit
        ) {
            data[packedPosition]?.values?.forEach(action)
        }

        fun capture(
            key: Key?
        ): List<OpaqueComponentCandidate> {
            val candidates = ArrayList<OpaqueComponentCandidate>()
            for ((packedPosition, components) in data) {
                if (key == null) {
                    for (component in components.values) {
                        candidates.add(
                            OpaqueComponentCandidate(
                                packedPosition,
                                component.key,
                                component
                            )
                        )
                    }
                } else {
                    val component = components[key] ?: continue
                    candidates.add(
                        OpaqueComponentCandidate(
                            packedPosition,
                            component.key,
                            component
                        )
                    )
                }
            }
            candidates.sortWith(
                compareBy<OpaqueComponentCandidate>(
                    { it.packedPosition },
                    { it.key }
                )
            )
            return Collections.unmodifiableList(candidates)
        }

        fun isCurrent(candidate: OpaqueComponentCandidate): Boolean {
            return data[candidate.packedPosition]?.get(candidate.key) ===
                candidate.expectedComponent
        }

        fun removeIfCurrent(candidate: OpaqueComponentCandidate): Boolean {
            if (!isCurrent(candidate)) return false
            remove(candidate.packedPosition, candidate.key)
            return true
        }

        fun clear() {
            data.clear()
        }
    }

}
