package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleNotifications
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.breaking.BreakPolicy
import ru.fornax.fornaxcitadel.modules.breaking.BreakProcessor
import ru.fornax.fornaxcitadel.modules.breaking.BreakService
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthBehavior
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.fortification.Fortification
import ru.fornax.fornaxcitadel.modules.ownership.Owner
import ru.fornax.fornaxcitadel.modules.place.PlaceProcessor
import ru.fornax.fornaxcitadel.modules.place.PlaceService
import ru.fornax.fornaxcitadel.modules.remove.RemovalPolicy
import ru.fornax.fornaxcitadel.modules.remove.RemoveProcessor
import ru.fornax.fornaxcitadel.modules.remove.RemoveService

/**
 * Core lifecycle entry point. Construct and register definitions before loading chunks.
 *
 * Register external protection checks on [actions.validatorCoordinator]. A rejection always
 * wins, including for owners. Explicit administrative removal is denied unless [removalPolicy]
 * authorizes it. Source keys are attribution.
 *
 * Services and the component manager expose trusted low-level operations; external requests
 * must enter through [actions]. All access is on the owning game thread. Observers may post
 * actions, and the host calls actions.drainPosted() after the current operation in the same tick.
 * This module changes component data only; it has no Minecraft/Paper dependencies.
 */
class CitadelModule @JvmOverloads constructor(
    private val manager: SimpleComponentManager,
    removalPolicy: RemovalPolicy = RemovalPolicy.DENY
) {
    val definitions = CitadelDefinitionRegistry()
    val lifecycle = CitadelLifecycleNotifications()
    val actions = ActionManager(manager)
    val remove = RemoveService(manager, lifecycle, removalPolicy)
    val health = HealthService(manager, remove)
    val place = PlaceService(manager, definitions, lifecycle)
    val breaking = BreakService(manager, health, remove, lifecycle)

    init {
        manager.registerComponent(CitadelKind.KEY.namespace, CitadelKind.KEY.key, CitadelKind::class.java)
        manager.registerComponent(Owner.KEY.namespace, Owner.KEY.key, Owner::class.java)
        manager.registerComponent(Health.KEY.namespace, Health.KEY.key, Health::class.java)
        manager.registerComponent(Maturing.KEY.namespace, Maturing.KEY.key, Maturing::class.java)
        manager.registerComponent(Fortification.KEY.namespace, Fortification.KEY.key, Fortification::class.java)
        actions.processorCoordinator.registerProcessor(PlaceProcessor(place))
        actions.processorCoordinator.registerProcessor(BreakProcessor(breaking))
        actions.processorCoordinator.registerProcessor(RemoveProcessor(remove))
        health.init(actions.processorCoordinator)

        // Direct damage requests must honor the same type restrictions as breaking attempts.
                // The processor handles missing or unloaded targets.
        actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun validate(action: DamageAction): ActionValidator.Result {
                val target = manager.findCitadel(action.citadelW, action.citadelX, action.citadelY, action.citadelZ)
                    ?: return ActionValidator.Result.Neutral
                return if (breaking.allowsDamage(action.context, target)) ActionValidator.Result.Success
                else ActionValidator.Result.Rejected
            }
        })
    }

    /**
     * Registers a type with its rules. When [healthBehavior] is supplied, a HealthInitializer
     * is appended after custom initializers so it can see Owner/Fortification/etc. in the draft.
     * Do not also add a HealthInitializer manually. Existing definitions cannot be replaced.
     */
    @JvmOverloads
    fun registerDefinition(
        definition: CitadelDefinition,
        breakPolicy: BreakPolicy,
        healthBehavior: HealthBehavior? = null
    ) {
        require(definitions.find(definition.key) == null) { "Citadel definition already registered: ${definition.key}" }
        require(breaking.findPolicy(definition.key) == null) { "Break policy already registered: ${definition.key}" }
        require(health.findBehavior(definition.key) == null) { "Health behavior already registered: ${definition.key}" }
        val prepared = if (healthBehavior == null) definition else CitadelDefinition(
            definition.key, definition.initializers + HealthInitializer(healthBehavior)
        )
        definitions.register(prepared)
        breaking.registerPolicy(definition.key, breakPolicy)
        if (healthBehavior != null) health.registerBehavior(definition.key, healthBehavior)
    }
}
