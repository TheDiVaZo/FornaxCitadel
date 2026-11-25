package ru.fornax.fornaxcitadel.core.resolver

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition

internal object ExactPositionResolver : CitadelResolver {
    override fun getResponsiblePosition(position: CitadelPosition) = position

    override fun getProtectionCandidates(position: CitadelPosition) = sequenceOf(position)
}
