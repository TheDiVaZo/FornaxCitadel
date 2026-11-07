package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.ComponentType
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.pool.WXZCordPool
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelEntity
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
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
class ComponentManager(
    private val citadelStorage: CitadelStorage,
    private val typeManager: TypeManager
) {
    private val pool = WXZCordPool()

    /** Register before loading chunks. Does not decode opaque data in already loaded chunks. */
    fun <T : Component> registerComponent(key: Key, componentClass: Class<T>) {
        typeManager.registerType(key, componentClass)
    }

    /**
     * Low-level creation used by the placement service after its validators and initializers.
     * All types are checked before insertion. A successful call consumes the draft and marks
     * the loaded chunk dirty. This method does not publish gameplay lifecycle notifications.
     */
    fun createCitadel(draft: MutableCitadelDraft): ActionResult {
        val prepared = draft.componentsForCommit().map { (componentClass, component) ->
            prepareComponent(componentClass, component)
        }
        val position = draft.position
        val chunk = findChunk(position.world, position.x, position.y, position.z)
            ?: return ActionResult.DENIED
        if (chunk.getCitadel(position.x and 15, position.y, position.z and 15) != null) {
            return ActionResult.DENIED
        }

        val entity = chunk.putCitadel(position.x and 15, position.y, position.z and 15)
        try {
            for ((type, component) in prepared) chunk.putComponent(entity, type, component)
            draft.markCommitted()
        } catch (failure: Exception) {
            chunk.removeCitadel(position.x and 15, position.y, position.z and 15)
            throw failure
        }
        return ActionResult.SUCCESS
    }

    fun findCitadel(world: UUID, x: Int, y: Int, z: Int): CitadelHandle? {
        val chunk = findChunk(world, x, y, z) ?: return null
        val entity = chunk.getCitadel(x and 15, y, z and 15) ?: return null
        return CitadelHandle(this, CitadelPosition(world, x, y, z), entity.id)
    }

    /**
     * Visits citadels in one loaded chunk within the inclusive [minY]..[maxY] range.
     * Heights are clipped to the supported storage bounds; an entirely outside range or an
     * unloaded chunk produces no callbacks. This query never loads a chunk or interns its key.
     * [chunkX] and [chunkZ] are chunk coordinates in -32768..32767; handles use global block
     * coordinates and retain the identity of the particular loaded entity.
     *
     * [minY] must not exceed [maxY], including for unloaded chunks. Do not structurally modify
     * citadels or their components, or unload the chunk, during a callback. Reading does not
     * mark the chunk dirty; live component mutations still require [editComponent] or [markDirty].
     */
    fun forEachCitadel(
        world: UUID,
        chunkX: Int,
        chunkZ: Int,
        minY: Int,
        maxY: Int,
        action: (CitadelHandle) -> Unit
    ) {
        require(minY <= maxY) { "Minimum height $minY exceeds maximum height $maxY" }
        require(chunkX in Short.MIN_VALUE..Short.MAX_VALUE) { "Invalid chunk x: $chunkX" }
        require(chunkZ in Short.MIN_VALUE..Short.MAX_VALUE) { "Invalid chunk z: $chunkZ" }
        if (maxY < ChunkCord.MIN_Y || minY >= ChunkCord.MIN_Y + ChunkCord.WORLD_HEIGHT) return
        val chunk = citadelStorage.getIfChunkLoaded(WXZCord(world, chunkX, chunkZ)) ?: return
        val baseX = chunkX shl 4
        val baseZ = chunkZ shl 4
        chunk.forEachCitadelInYRange(minY, maxY) { x, y, z, entity ->
            action(CitadelHandle(this, CitadelPosition(world, baseX + x, y, baseZ + z), entity.id))
        }
    }

    /** False for a handle owned by a different manager, removed entity, or unloaded chunk. */
    fun isCurrent(handle: CitadelHandle): Boolean =
        withCurrentCitadel(handle) { _, _ -> true } ?: false

    /** Removes only the original entity. A replacement at the same position is left untouched. */
    fun removeCitadel(handle: CitadelHandle): Boolean =
        withCurrentCitadel(handle) { chunk, _ ->
            chunk.removeCitadel(handle.x and 15, handle.y, handle.z and 15) != null
        } ?: false

    internal fun <T : Component> getComponent(handle: CitadelHandle, componentClass: Class<T>): T? {
        val type = requireType(componentClass)
        return withCurrentCitadel(handle) { chunk, entity -> chunk.getComponent(entity, type) }
    }

    internal fun <T : Component> editComponent(
        handle: CitadelHandle, componentClass: Class<T>, action: (T) -> Unit
    ): Boolean {
        val type = requireType(componentClass)
        return withCurrentCitadel(handle) { chunk, entity ->
            val component = chunk.getComponent(entity, type) ?: return@withCurrentCitadel false
            try {
                action(component)
            } finally {
                // The callback may have unloaded or replaced the chunk; mark the original instance.
                chunk.markDirty()
            }
            true
        } ?: false
    }

    internal fun <T : Component> markDirty(
        handle: CitadelHandle, componentClass: Class<T>, component: T
    ): Boolean {
        val type = requireType(componentClass)
        return withCurrentCitadel(handle) { chunk, entity ->
            if (chunk.getComponent(entity, type) !== component) return@withCurrentCitadel false
            chunk.markDirty()
            true
        } ?: false
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
     * The first callback argument is the citadel's runtime ID, which changes after reloading.
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
        action: (citadelId: Int, x: Int, y: Int, z: Int, component: T) -> Unit
    ) {
        val type = requireType(componentClass)
        val chunk = citadelStorage.getIfChunkLoaded(pool.get(world, chunkX, chunkZ)) ?: return
        val baseX = chunkX shl 4
        val baseZ = chunkZ shl 4
        chunk.forEachComponentOfType(type) { entity, component ->
            val position = entity.packedPosition
            action(
                entity.id,
                baseX + ChunkCord.unpackX(position),
                ChunkCord.unpackY(position),
                baseZ + ChunkCord.unpackZ(position),
                component
            )
        }
    }

    /**
     * Visits this component type within the inclusive range `y - yDelta..y + yDelta`.
     * Layers are visited in order `y, y - 1, y + 1, y - 2, y + 2, ...`, skipping heights
     * outside the storage bounds. [y] must be a valid storage height and [yDelta] nonnegative;
     * zero visits only the center layer. Arguments are checked even if the chunk is unloaded.
     *
     * [chunkX] and [chunkZ] are chunk coordinates; [action] receives the runtime citadel ID,
     * global block coordinates, and the live component. An unloaded chunk or a missing
     * component type in the chunk produces no callbacks.
     *
     * Do not add, remove, or replace components, or unload the chunk, during iteration.
     * Reading does not mark the chunk dirty; call [markDirty] after mutating a component's fields.
     */
    fun <T : Component> forEachComponentOfType(
        componentClass: Class<T>,
        world: UUID,
        chunkX: Int,
        chunkZ: Int,
        y: Int,
        yDelta: Int,
        action: (citadelId: Int, x: Int, y: Int, z: Int, component: T) -> Unit
    ) {
        val type = requireType(componentClass)
        ChunkCord.requireValidYRange(y, yDelta)
        val chunk = citadelStorage.getIfChunkLoaded(pool.get(world, chunkX, chunkZ)) ?: return
        val baseX = chunkX shl 4
        val baseZ = chunkZ shl 4
        chunk.forEachComponentOfType(type, y, yDelta) { entity, component ->
            val position = entity.packedPosition
            action(
                entity.id,
                baseX + ChunkCord.unpackX(position),
                ChunkCord.unpackY(position),
                baseZ + ChunkCord.unpackZ(position),
                component
            )
        }
    }

    /**
     * Inserts a live instance into an existing position and returns the previous one.
     * The chunk and position must already exist. Use the placement service to create a citadel.
     * Marks the chunk dirty; does not immediately save it.
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
        val entity = checkNotNull(chunk.getCitadel(x and 15, y, z and 15)) {
            "Citadel $world [$x, $y, $z] does not exist"
        }
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

    @Suppress("UNCHECKED_CAST")
    private fun prepareComponent(
        componentClass: Class<out Component>, component: Component
    ): Pair<ComponentType<Component>, Component> {
        require(componentClass.isInstance(component)) {
            "Component ${component.javaClass.name} does not match ${componentClass.name}"
        }
        return requireType(componentClass as Class<Component>) to component
    }

    /** Resolves one loaded entity for an operation. Runtime IDs are never reused. */
    private inline fun <R> withCurrentCitadel(
        handle: CitadelHandle, action: (ChunkCord, CitadelEntity) -> R
    ): R? {
        if (handle.manager !== this) return null
        val chunk = findChunk(handle.world, handle.x, handle.y, handle.z) ?: return null
        val entity = chunk.getCitadel(handle.x and 15, handle.y, handle.z and 15) ?: return null
        if (entity.id != handle.runtimeId) return null
        return action(chunk, entity)
    }

    private fun findChunk(world: UUID, x: Int, y: Int, z: Int): ChunkCord? {
        require(y in ChunkCord.MIN_Y until (ChunkCord.MIN_Y + ChunkCord.WORLD_HEIGHT)) {
            "Invalid y: $y"
        }
        return citadelStorage.getIfChunkLoaded(pool.get(world, x shr 4, z shr 4))
    }
}
