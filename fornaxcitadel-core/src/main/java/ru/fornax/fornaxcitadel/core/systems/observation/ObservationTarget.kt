package ru.fornax.fornaxcitadel.core.systems.observation

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition

/**
 * Identity retained for cleanup after removal or unload, when live components are unavailable.
 * A new citadel at the same position has a different runtime ID. Do not persist this identity.
 */
data class ObservationTarget(
    val position: CitadelPosition,
    val runtimeId: Int
)
