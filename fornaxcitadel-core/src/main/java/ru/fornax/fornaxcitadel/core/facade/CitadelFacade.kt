package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult

/**
 * Resolves gameplay positions and accesses their reinforcement on the owning game thread.
 * This is a trusted data operation: external mutations must still enter through ActionManager.
 */
class CitadelFacade @JvmOverloads constructor(
    internal val componentManager: ComponentManager,
    private val resolver: CitadelResolver = DirectCitadelResolver(componentManager)
) {
    fun getResponsiblePosition(position: CitadelPosition): CitadelPosition {
        requireAvailable(position)
        return resolver.getResponsiblePosition(position).also(::requireAvailable)
    }

    /** Null means no reinforcement; unavailable data is reported separately. */
    fun findProtecting(position: CitadelPosition): CitadelHandle? {
        if (!isSupported(position)) return null
        requireAvailable(position)
        val handle = resolver.getReinforcementProtecting(position) ?: return null
        requireAvailable(handle.position)
        check(componentManager.isCurrent(handle)) { "Resolver returned a stale or foreign citadel handle" }
        return handle
    }

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
        return checkNotNull(componentManager.findCitadel(
            responsible.world, responsible.x, responsible.y, responsible.z
        ))
    }

    private fun canCreate(source: CitadelPosition, responsible: CitadelPosition): Boolean = try {
        requireAvailable(source)
        requireAvailable(responsible)
        findProtecting(source) == null && (source == responsible || findProtecting(responsible) == null)
    } catch (_: CitadelResolutionUnavailableException) {
        false
    }

    private fun requireAvailable(position: CitadelPosition) {
        if (!isSupported(position) ||
            !componentManager.isChunkLoaded(position.world, position.x shr 4, position.z shr 4)) {
            throw CitadelResolutionUnavailableException(position)
        }
    }

    private fun isSupported(position: CitadelPosition): Boolean =
        CitadelPosition.isSupported(position.x, position.y, position.z)
}
