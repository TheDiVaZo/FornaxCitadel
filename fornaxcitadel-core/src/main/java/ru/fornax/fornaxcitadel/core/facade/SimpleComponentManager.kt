package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.ComponentType
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.pool.WXZCordPool
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Component access by global block coordinates. Chunk lifecycle methods take chunk coordinates.
 * [typeManager] must be the same registry used by the storage's snapshot mapper.
 *
 * This facade is not thread-safe. Access components on the owning game thread, while the storage
 * is active. Returned and inserted components are live instances, not copies. Do not share an
 * instance between positions or keep using it after replacement, removal, unload, or shutdown.
 * Use [editComponent], or call [markDirty] after each direct mutation, including nested data.
 * The coordinate pool supports chunk x and z in -32768..32767.
 */
class SimpleComponentManager(
    private val citadelStorage: CitadelStorage,
    private val typeManager: TypeManager
) {
    private val pool = WXZCordPool()

    /** Register before loading chunks. Does not decode opaque data in already loaded chunks. */
    fun <T : Component> registerComponent(namespace: String, key: String, componentClass: Class<T>) {
        typeManager.registerType(Key(namespace, key), componentClass)
    }

    /** Returns the live instance, or null if the chunk, position, or component is absent. */
    fun <T : Component> getComponent(
        componentClass: Class<T>, world: UUID, x: Int, y: Int, z: Int
    ): T? {
        val type = requireType(componentClass)
        val chunk = findChunk(world, x, y, z) ?: return null
        val entity = chunk.getCitadel(x and 15, y, z and 15) ?: return null
        return chunk.getComponent(entity, type)
    }

    /**
     * Visits positions with this component type in the specified loaded chunk.
     * [chunkX] and [chunkZ] are chunk coordinates; [action] receives global block coordinates.
     * An unloaded chunk or a missing component type in the chunk produces no callbacks.
     *
     * Do not add, remove, or replace components, or unload the chunk, during iteration.
     * Reading does not mark the chunk dirty; call [markDirty] after mutating a component's fields.
     */
    fun <T : Component> forEachComponentOfType(
        componentClass: Class<T>,
        world: UUID,
        chunkX: Int,
        chunkZ: Int,
        action: (x: Int, y: Int, z: Int, component: T) -> Unit
    ) {
        val type = requireType(componentClass)
        val chunk = citadelStorage.getIfChunkLoaded(pool.get(world, chunkX, chunkZ)) ?: return
        val baseX = chunkX shl 4
        val baseZ = chunkZ shl 4
        chunk.forEachComponentOfType(type) { entity, component ->
            val position = entity.packedPosition
            action(
                baseX + ChunkCord.unpackX(position),
                ChunkCord.unpackY(position),
                baseZ + ChunkCord.unpackZ(position),
                component
            )
        }
    }

    /**
     * Inserts a live instance, creating the position if necessary, and returns the previous one.
     * The chunk must already be loaded. Marks the chunk dirty; does not immediately save it.
     */
    fun <T : Component> putComponent(
        componentClass: Class<T>, world: UUID, x: Int, y: Int, z: Int, component: T
    ): T? {
        val type = requireType(componentClass)
        require(componentClass.isInstance(component)) {
            "Component ${component.javaClass.name} does not match ${componentClass.name}"
        }
        val chunk = checkNotNull(findChunk(world, x, y, z)) {
            "Chunk $world [${x shr 4}, ${z shr 4}] is not loaded"
        }
        val entity = chunk.getCitadel(x and 15, y, z and 15)
            ?: chunk.putCitadel(x and 15, y, z and 15)
        return chunk.putComponent(entity, type, component)
    }

    /** Removes decoded or opaque data of this type and returns the removed live instance, if any. */
    fun <T : Component> removeComponent(
        componentClass: Class<T>, world: UUID, x: Int, y: Int, z: Int
    ): T? {
        val type = requireType(componentClass)
        val chunk = findChunk(world, x, y, z) ?: return null
        val entity = chunk.getCitadel(x and 15, y, z and 15) ?: return null
        return chunk.removeComponent(entity, type)
    }

    /** Removes the position and all its decoded and opaque components; false if absent/unloaded. */
    fun removeAllComponents(world: UUID, x: Int, y: Int, z: Int): Boolean {
        val chunk = findChunk(world, x, y, z) ?: return false
        return chunk.removeCitadel(x and 15, y, z and 15) != null
    }

    /**
     * Edits the live component synchronously; false if it is absent or the chunk is unloaded.
     * Marks dirty even if [action] throws, because partial changes are not rolled back.
     * Only mutate the supplied component here; do not unload, save, or replace it inside [action].
     * Mutations made through a retained reference after [action] returns still need [markDirty].
     */
    fun <T : Component> editComponent(
        componentClass: Class<T>, world: UUID, x: Int, y: Int, z: Int, action: (T) -> Unit
    ): Boolean {
        val type = requireType(componentClass)
        val chunk = findChunk(world, x, y, z) ?: return false
        val entity = chunk.getCitadel(x and 15, y, z and 15) ?: return false
        val component = chunk.getComponent(entity, type) ?: return false
        try {
            action(component)
        } finally {
            chunk.markDirty()
        }
        return true
    }

    /**
     * Marks a directly mutated component for the next save. Returns false if [component] is no
     * longer the instance stored at this position, including after an unload and reload.
     */
    fun <T : Component> markDirty(
        componentClass: Class<T>, world: UUID, x: Int, y: Int, z: Int, component: T
    ): Boolean {
        val type = requireType(componentClass)
        val chunk = findChunk(world, x, y, z) ?: return false
        val entity = chunk.getCitadel(x and 15, y, z and 15) ?: return false
        if (chunk.getComponent(entity, type) !== component) return false
        chunk.markDirty()
        return true
    }

    fun isChunkLoaded(world: UUID, chunkX: Int, chunkZ: Int): Boolean {
        return citadelStorage.getIfChunkLoaded(pool.get(world, chunkX, chunkZ)) != null
    }

    /** Loads storage data for a chunk without exposing the internal chunk instance. */
    fun loadChunk(world: UUID, chunkX: Int, chunkZ: Int): CompletableFuture<Void> {
        return citadelStorage.loadChunk(pool.get(world, chunkX, chunkZ)).thenApply<Void> { null }
    }

    /** Saves dirty data and unloads the chunk. Previously obtained component references expire. */
    fun unloadChunk(world: UUID, chunkX: Int, chunkZ: Int): CompletableFuture<Void> {
        return citadelStorage.unloadChunk(pool.get(world, chunkX, chunkZ))
    }

    /** Saves dirty chunks. Does not discover direct mutations that were not marked dirty. */
    fun saveAll(): CompletableFuture<Void> = citadelStorage.saveAll()

    /** Stop component access before calling this. Observe the future for persistence failures. */
    fun closeAndSaveAll(): CompletableFuture<Void> = citadelStorage.closeAndSaveAll()

    private fun <T : Component> requireType(componentClass: Class<T>): ComponentType<T> {
        return requireNotNull(typeManager.getType(componentClass)) {
            "Component ${componentClass.name} is not registered"
        }
    }

    private fun findChunk(world: UUID, x: Int, y: Int, z: Int): ChunkCord? {
        require(y in ChunkCord.MIN_Y until (ChunkCord.MIN_Y + ChunkCord.WORLD_HEIGHT)) {
            "Invalid y: $y"
        }
        return citadelStorage.getIfChunkLoaded(pool.get(world, x shr 4, z shr 4))
    }
}
