package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolver
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID

/**
 * Resolves gameplay positions and accesses their reinforcement on the owning game thread.
 * External callers request changes through [ActionFacade] so their actions pass validation.
 */
class CitadelFacade(
    private val componentManager: ComponentManager,
    private val resolver: CitadelResolver
) : ReadableCitadelFacade {
    fun <T : Component<*>> registerComponent(key: Key, componentClass: Class<T>) =
        componentManager.registerComponent(key, componentClass)

    override fun getResponsiblePosition(position: CitadelPosition): CitadelPosition {
        requireAvailable(position)
        return resolver.getResponsiblePosition(position).also(::requireAvailable)
    }

    /** Null means no reinforcement; unavailable data is reported separately. */
    override fun findProtecting(position: CitadelPosition): CitadelHandle? {
        if (!isSupported(position)) return null
        requireAvailable(position)
        return resolver.getProtectionCandidates(position).firstNotNullOfOrNull { candidate ->
            requireAvailable(candidate)
            findExact(candidate)
        }
    }

    /** Looks up the stored reinforcement at these exact coordinates. Unavailable positions return null. */
    fun findExact(position: CitadelPosition): CitadelHandle? {
        if (!isSupported(position)) return null
        return componentManager.findCitadel(position.world, position.x, position.y, position.z)
    }

    fun isChunkLoaded(world: UUID, chunkX: Int, chunkZ: Int): Boolean =
        componentManager.isChunkLoaded(world, chunkX, chunkZ)

    override fun forEachCitadel(
        world: UUID,
        chunkX: Int,
        chunkZ: Int,
        minY: Int,
        maxY: Int,
        visit: (ReadableHandle) -> Unit
    ) = componentManager.forEachCitadel(world, chunkX, chunkZ, minY, maxY, visit)

    /** Checks that the handle identifies a currently loaded entity owned by this facade's manager. */
    fun isCurrent(handle: ReadableHandle): Boolean = componentManager.isCurrent(handle)

    /** Removes the entity identified by this handle. Returns false for expired or foreign handles. */
    fun removeCitadel(handle: CitadelHandle): Boolean = componentManager.removeCitadel(handle)

    /**
     * Rejects unavailable or protected positions before allocating a draft. The callback runs once
     * at the resolved position and may only fill its draft. It must not mutate the world or storage.
     * Callback/commit failures propagate without publishing a partially initialized reinforcement.
     */
    fun createCitadel(position: CitadelPosition, fillDraft: (CitadelDraft) -> Unit): CitadelHandle? {
        if (!isSupported(position)) return null
        val responsible = try {
            getResponsiblePosition(position)
        } catch (_: CitadelResolutionUnavailableException) {
            return null
        }
        if (!canCreate(position, responsible)) return null

        val draft = MutableCitadelDraft(responsible)
        fillDraft(draft)
        // A draft does not reserve storage. Keep the chosen position, but recheck before publishing.
        if (!canCreate(position, responsible)) return null
        if (componentManager.createCitadel(draft) != ActionResult.SUCCESS) return null
        return checkNotNull(findExact(responsible))
    }

    private fun canCreate(source: CitadelPosition, responsible: CitadelPosition): Boolean = try {
        requireAvailable(source)
        requireAvailable(responsible)
        findProtecting(source) == null && (source == responsible || findProtecting(responsible) == null)
    } catch (_: CitadelResolutionUnavailableException) {
        false
    }

    private fun requireAvailable(position: CitadelPosition) {
        if (!isSupported(position) || !isChunkLoaded(position.world, position.x shr 4, position.z shr 4)) {
            throw CitadelResolutionUnavailableException(position)
        }
    }

    private fun isSupported(position: CitadelPosition): Boolean =
        CitadelPosition.isSupported(position.x, position.y, position.z)
}
