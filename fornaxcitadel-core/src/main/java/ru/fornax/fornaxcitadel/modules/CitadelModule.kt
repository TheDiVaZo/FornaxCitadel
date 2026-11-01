package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleNotifications
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.modules.health.DamagePolicy
import ru.fornax.fornaxcitadel.modules.health.HealthBehavior
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.place.PlaceService
import ru.fornax.fornaxcitadel.modules.remove.RemovalPolicy
import ru.fornax.fornaxcitadel.modules.remove.RemoveService

/**
 * Core lifecycle entry point. Construct and register definitions before loading chunks;
 * call [enable] to attach action handlers and [disable] to detach them without deleting data.
 *
 * Register external protection checks on [actions.validatorCoordinator]. A rejection always
 * wins. Explicit administrative removal is denied unless [removalPolicy]
 * authorizes it. Source keys are attribution.
 *
 * Services and the component manager expose trusted low-level operations; external requests
 * must enter through [actions]. All access is on the owning game thread. Observers may post
 * actions, and the host calls actions.drainPosted() after the current operation in the same tick.
 * This module changes component data only; it has no Minecraft/Paper dependencies.
 */
class CitadelModule @JvmOverloads constructor(
    manager: ComponentManager,
    removalPolicy: RemovalPolicy = RemovalPolicy.DENY
) : LifecycleService {
    val definitions = CitadelDefinitionRegistry()
    val lifecycle = CitadelLifecycleNotifications()
    val actions = ActionManager(manager)
    val place = PlaceService(actions, definitions, lifecycle)
    val remove = RemoveService(actions, lifecycle, removalPolicy)
    val health = HealthService(actions, remove)
    private val services: List<LifecycleService> = listOf(remove, health, place)

    override fun enable() {
        try {
            services.forEach { it.enable() }
        } catch (failure: Exception) {
            disable()
            throw failure
        }
    }

    override fun disable() {
        services.asReversed().forEach { it.disable() }
        actions.clearPosted()
    }

    /**
     * Registers a type with its rules. When [healthBehavior] is supplied, a HealthInitializer
     * is appended after custom initializers so it can see their components in the draft.
     * Do not also add a HealthInitializer manually. Existing definitions cannot be replaced.
     */
    @JvmOverloads
    fun registerDefinition(
        definition: CitadelDefinition,
        damagePolicy: DamagePolicy,
        healthBehavior: HealthBehavior? = null
    ) {
        require(definitions.find(definition.key) == null) { "Citadel definition already registered: ${definition.key}" }
        require(health.findPolicy(definition.key) == null) { "Damage policy already registered: ${definition.key}" }
        require(health.findBehavior(definition.key) == null) { "Health behavior already registered: ${definition.key}" }
        val prepared = if (healthBehavior == null) definition else CitadelDefinition(
            definition.key, definition.initializers + HealthInitializer(healthBehavior)
        )
        definitions.register(prepared)
        health.registerPolicy(definition.key, damagePolicy)
        if (healthBehavior != null) health.registerBehavior(definition.key, healthBehavior)
    }
}
