package ru.fornax.fornaxcitadel.paper

import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.modules.health.HealthDefinition
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes

internal fun typesWithItem(item: Key, type: Key = PaperCitadelTypes.REINFORCED): CitadelTypesSettings =
    CitadelTypesSettings(CitadelTypesSettings.DEFAULT_TYPES.toMutableMap().apply {
        this["reinforced"] = getValue("reinforced").copy(health = HealthDefinition(100.0))
        this[type.key] = getValue(type.key).copy(item = item)
    })
