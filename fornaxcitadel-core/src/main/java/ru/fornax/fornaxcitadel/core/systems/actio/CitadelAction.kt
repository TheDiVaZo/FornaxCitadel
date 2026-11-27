package ru.fornax.fornaxcitadel.core.systems.actio

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition

interface CitadelAction: Action {
    val position: CitadelPosition
}