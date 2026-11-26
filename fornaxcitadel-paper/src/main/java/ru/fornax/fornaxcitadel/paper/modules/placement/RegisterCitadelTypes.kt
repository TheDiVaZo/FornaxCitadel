package ru.fornax.fornaxcitadel.paper.modules.placement

import ru.fornax.fornaxcitadel.addon.CoreGameplay
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.modules.health.MaturationDefinition
import ru.fornax.fornaxcitadel.modules.health.MaturingInitializer
import java.time.Duration

fun CitadelTypesSettings.register(coreGameplay: CoreGameplay) {
    val health = coreGameplay.health
    val initializers = listOf(HealthInitializer(health), MaturingInitializer())
    for ((name, settings) in types) {
        val key = Key("fornaxcitadel", name)
        coreGameplay.registerDefinition(CitadelDefinition(key, initializers, level = settings.lvl, itemKey = settings.item))
        health.registerDefinition(key, settings.health)
        health.maturation.registerDefinition(key, MaturationDefinition(
            Duration.ofSeconds(settings.maturing?.durationSeconds ?: 0),
            settings.maturing?.initialDamageMultiplier ?: 1.0
        ))
    }
}
