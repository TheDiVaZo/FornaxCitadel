package ru.fornax.fornaxcitadel.paper

import org.bukkit.entity.Player
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.ObservationFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolver
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.paper.material.MaterialFacade
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifierService

class FornaxCitadelAPI(
    val citadel: CitadelFacade,
    val action: ActionFacade,
    val resolver: CitadelResolver,
    val observation: ObservationFacade<Player>,
    val materials: MaterialFacade,
    /** Modifiers for damage dealt by players mining reinforced blocks. */
    val playerDamage: PlayerDamageModifierService,
    /** Shared with the gameplay services. Register addon types on the server's main thread. */
    val definitions: CitadelDefinitionRegistry,
    val health: HealthService
)
