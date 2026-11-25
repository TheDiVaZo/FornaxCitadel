package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.systems.observation.Observation
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationService
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationSubscription
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationSystem
import java.util.UUID

class ObservationFacade<V>(private val service: ObservationService<V>) {
    fun registerSystem(system: ObservationSystem<V>) = service.registerSystem(system)

    fun registerViewer(
        viewerId: UUID,
        viewer: V,
        observation: Observation<V>
    ): ObservationSubscription = service.registerViewer(viewerId, viewer, observation)
}
