package ru.fornax.fornaxcitadel.addon

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.systems.action.ActionMeta
import ru.fornax.fornaxcitadel.core.systems.action.ExistingCitadelValidator
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.DamageProcessor
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.health.MaturationService
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.health.MaturingDamageModifier
import ru.fornax.fornaxcitadel.modules.place.PlaceActionValidator
import ru.fornax.fornaxcitadel.modules.place.PlaceProcessor
import ru.fornax.fornaxcitadel.modules.place.PlaceService
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveProcessor
import ru.fornax.fornaxcitadel.modules.remove.RemoveService

/** Registers the persisted core types at construction, before the first chunk is loaded. */
class CoreGameplay(
    private val citadelFacade: CitadelFacade,
    private val actionFacade: ActionFacade,
) {
    val definitions = CitadelDefinitionRegistry()

    val place = PlaceService(definitions, citadelFacade)
    val remove = RemoveService(citadelFacade)
    val health = HealthService(remove, citadelFacade, MaturationService())

    private val maturationModifier = MaturingDamageModifier(health.maturation)

    init {
        citadelFacade.registerComponent(CitadelKind.KEY, CitadelKind::class.java)
        citadelFacade.registerComponent(CitadelOwner.KEY, CitadelOwner::class.java)
        citadelFacade.registerComponent(Health.KEY, Health::class.java)
        citadelFacade.registerComponent(Maturing.KEY, Maturing::class.java)

        health.registerFirstModifier(maturationModifier)

        actionFacade.registerAction(RemoveProcessor(remove), ActionMeta.DEFAULT)
        actionFacade.registerValidator(ExistingCitadelValidator(RemoveAction::class.java, citadelFacade))

        actionFacade.registerAction(DamageProcessor(health), ActionMeta.DEFAULT)
        actionFacade.registerValidator(ExistingCitadelValidator(DamageAction::class.java, citadelFacade))

        actionFacade.registerAction(PlaceProcessor(place), ActionMeta.DEFAULT)
        actionFacade.registerValidator(PlaceActionValidator(citadelFacade, definitions))
    }

    /** Registers the initial components of a citadel type before its chunks are loaded. */
    fun registerDefinition(definition: CitadelDefinition) {
        require(definitions.find(definition.key) == null) { "Citadel definition already registered: ${definition.key}" }
        definitions.register(definition)
    }
}
