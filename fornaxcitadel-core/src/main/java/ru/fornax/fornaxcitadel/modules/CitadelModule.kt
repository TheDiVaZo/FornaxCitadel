package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleNotifications
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolver
import ru.fornax.fornaxcitadel.core.resolver.DirectCitadelResolver
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.systems.action.ActionMetaRegistry.ActionMeta
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.place.PlaceService
import ru.fornax.fornaxcitadel.modules.queue.ActionQueueService
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveService

/**
 * Core lifecycle entry point. Construct and register definitions before loading chunks;
 * call [enable] to attach action handlers and [disable] to detach them without deleting data.
 *
 * Register read-only authorization checks on [actions.validatorCoordinator]. Damage, explicit
 * destruction, administrative removal and dismantling require a validator's Success; a rejection always wins.
 * This module validates target availability and placement prerequisites before execution.
 * These prerequisite checks never grant authorization. Definitions configure initial components;
 * source keys are attribution.
 *
 * Services and the component manager expose trusted low-level operations; external requests
 * must enter through [actions]. All access is on the owning game thread. Observers may post
 * actions; [actionQueue] drains them on subsequent ticks while this module is enabled.
 * This module changes component data only; it has no Minecraft/Paper dependencies.
 */
class CitadelModule @JvmOverloads constructor(
    manager: ComponentManager,
    scheduler: Scheduler,
    maxActionsPerTick: Int = 1024,
    resolver: CitadelResolver = DirectCitadelResolver(manager),
    actionFailureHandler: (Exception) -> Unit = ActionQueueService.DEFAULT_FAILURE_HANDLER
) : LifecycleService {

    val definitions = CitadelDefinitionRegistry()
    val lifecycle = CitadelLifecycleNotifications()
    val citadels = CitadelFacade(manager, resolver)
    val actions = ActionManager(manager, citadels = citadels).apply {
        actionMetaRegistry.register(RemoveAction.KEY, ActionMeta(false))
        actionMetaRegistry.register(DamageAction.KEY, ActionMeta(false))
        actionMetaRegistry.register(DismantleAction.KEY, ActionMeta(false))
    }
    val place = PlaceService(actions, definitions, lifecycle)
    val remove = RemoveService(actions, lifecycle)
    val health = HealthService(actions, remove)
    val actionQueue = ActionQueueService(actions, scheduler, maxActionsPerTick, actionFailureHandler)
    private val actionValidator = CitadelActionValidator(citadels, definitions)
    private val services: List<LifecycleService> = listOf(remove, health, place, actionQueue)

    override fun enable() {
        try {
            actions.validatorCoordinator.registerValidator(actionValidator)
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
        actions.validatorCoordinator.unregisterValidator(actionValidator)
        actions.clearPosted()
        failure?.let { throw it }
    }

    /**
     * Registers initial components for a type, including health and optional maturation.
     * Register authorization separately through validators. Existing definitions cannot be replaced.
     */
    fun registerDefinition(definition: CitadelDefinition) {
        require(definitions.find(definition.key) == null) { "Citadel definition already registered: ${definition.key}" }
        definitions.register(definition)
    }
}
