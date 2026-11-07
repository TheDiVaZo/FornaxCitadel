package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition

/** A required world or chunk is unavailable; callers must not interpret this as no protection. */
class CitadelResolutionUnavailableException(val position: CitadelPosition) :
    IllegalStateException("Citadel data is unavailable at $position")
