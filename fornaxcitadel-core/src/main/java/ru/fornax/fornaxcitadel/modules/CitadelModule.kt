package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleNotifications
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.modules.health.DamagePolicy
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.place.PlaceService
import ru.fornax.fornaxcitadel.modules.queue.ActionQueueService
import ru.fornax.fornaxcitadel.modules.remove.RemovalPolicy
import ru.fornax.fornaxcitadel.modules.remove.RemoveService

/**
 * Core lifecycle entry point. Construct and register definitions before loading chunks;
 * call [enable] to attach action handlers and [disable] to detach them without deleting data.
 *
 * Register external protection checks on [actions.validatorCoordinator]. A rejection always
 * wins. Explicit administrative removal is denied unless [removalPolicy]
 * authorizes it. Explicit destruction requires [destructionPolicy]. Source keys are attribution.
 *
 * Services and the component manager expose trusted low-level operations; external requests
 * must enter through [actions]. All access is on the owning game thread. Observers may post
 * actions; [actionQueue] drains them on subsequent ticks while this module is enabled.
 * This module changes component data only; it has no Minecraft/Paper dependencies.
 */
class CitadelModule @JvmOverloads constructor(
    manager: ComponentManager,
    scheduler: Scheduler,
    removalPolicy: RemovalPolicy = RemovalPolicy.DENY,
    maxActionsPerTick: Int = 1024,
    destructionPolicy: DamagePolicy = DamagePolicy.DENY,
    actionFailureHandler: (Exception) -> Unit = ActionQueueService.DEFAULT_FAILURE_HANDLER
) : LifecycleService {
    constructor(
        manager: ComponentManager,
        scheduler: Scheduler,
        removalPolicy: RemovalPolicy,
        maxActionsPerTick: Int,
        actionFailureHandler: (Exception) -> Unit
    ) : this(manager, scheduler, removalPolicy, maxActionsPerTick, DamagePolicy.DENY, actionFailureHandler)

    val definitions = CitadelDefinitionRegistry()
    val lifecycle = CitadelLifecycleNotifications()
    val actions = ActionManager(manager)
    val place = PlaceService(actions, definitions, lifecycle)
    val remove = RemoveService(actions, lifecycle, removalPolicy)
    val health = HealthService(actions, remove, destructionPolicy)
    val actionQueue = ActionQueueService(actions, scheduler, maxActionsPerTick, actionFailureHandler)
    private val services: List<LifecycleService> = listOf(remove, health, place, actionQueue)

    override fun enable() {
        try {
            services.forEach { it.enable() }
        } catch (failure: Throwable) {
            try {
                disable()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    override fun disable() {
        var failure: Throwable? = null
        services.asReversed().forEach { service ->
            try {
                service.disable()
            } catch (cleanupFailure: Throwable) {
                val previous = failure
                if (previous == null) failure = cleanupFailure
                else if (previous !== cleanupFailure) previous.addSuppressed(cleanupFailure)
            }
        }
        actions.clearPosted()
        failure?.let { throw it }
    }

    /**
     * Registers a type with its damage rules. The definition's initializers prepare all
     * components, including health and optional maturation. Existing definitions cannot be replaced.
     */
    fun registerDefinition(
        definition: CitadelDefinition,
        damagePolicy: DamagePolicy
    ) {
        require(definitions.find(definition.key) == null) { "Citadel definition already registered: ${definition.key}" }
        require(health.findPolicy(definition.key) == null) { "Damage policy already registered: ${definition.key}" }
        definitions.register(definition)
        health.registerPolicy(definition.key, damagePolicy)
    }
}
